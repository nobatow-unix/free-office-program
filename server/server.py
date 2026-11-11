"""
ICQ New — server v3 (FastAPI + SQLite + WebSocket).

Login is by phone number + a 6-digit code. There is no SMS gateway: the code is shown
to the ADMIN (web panel /admin and optionally a Telegram message), and the admin sends
it to the user by SMS/WhatsApp from his own number.

v3: group chats, replies, editing, delete-for-everyone, forwarding, reactions, blocking,
profile "about" text, admin user list with ban/unban/delete. Old v2 databases are migrated
in place (users and history are kept).

Run:
    pip install -r requirements.txt
    uvicorn server:app --host 0.0.0.0 --port 8000

Env:
    ICQ_DB              database file (default icq_v2.db)
    ICQ_ADMIN_PASSWORD  password for /admin (login: admin). If unset, a random one is
                        generated once and stored in admin_password.txt
    ICQ_TG_TOKEN / ICQ_TG_CHAT   optional: Telegram bot token + chat id, the bot sends
                        "phone + code" to the admin every time a code is requested
"""
import asyncio
import contextvars
import json
import os
import html
import re
import secrets
import sqlite3

import db as icqdb
from cluster import CLUSTER
import cluster as icqcluster
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Tuple

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

DB_PATH = os.environ.get("ICQ_DB", "icq_v2.db")
AVATAR_DIR = os.environ.get("ICQ_AVATARS", "avatars")
UPLOAD_DIR = os.environ.get("ICQ_UPLOADS", "uploads")
MAX_UPLOAD = 4 * 1024 * 1024 * 1024  # 4 GB (Premium)
FREE_UPLOAD = 2 * 1024 * 1024 * 1024  # 2 GB without Premium

# ---- Premium (prices in Gold, the in-app coins; the admin tops balances up in the admin panel) ----
PREMIUM_PLANS = [  # months, price
    {"months": 1, "price": int(os.environ.get("ICQ_PREMIUM_1", "30"))},
    {"months": 3, "price": int(os.environ.get("ICQ_PREMIUM_3", "80"))},
    {"months": 12, "price": int(os.environ.get("ICQ_PREMIUM_12", "250"))},
]
GROUP_PLANS = [
    {"months": 1, "price": int(os.environ.get("ICQ_GROUP_PREMIUM_1", "50"))},
    {"months": 12, "price": int(os.environ.get("ICQ_GROUP_PREMIUM_12", "450"))},
]
GIFTS = [  # id, picture (emoji), name, price
    {"id": "rose", "emoji": "🌹", "name": "Роза", "price": 5},
    {"id": "teddy", "emoji": "🧸", "name": "Мишка", "price": 10},
    {"id": "cake", "emoji": "🎂", "name": "Торт", "price": 10},
    {"id": "heart", "emoji": "💝", "name": "Сердце", "price": 15},
    {"id": "gift", "emoji": "🎁", "name": "Сюрприз", "price": 20},
    {"id": "clover", "emoji": "🍀", "name": "Удача", "price": 25},
    {"id": "diamond", "emoji": "💎", "name": "Бриллиант", "price": 50},
    {"id": "crown", "emoji": "👑", "name": "Корона", "price": 100},
    {"id": "rocket", "emoji": "🚀", "name": "Ракета", "price": 150},
]
GIFT_BY_ID = {g["id"]: g for g in GIFTS}
PREMIUM_PERKS = [
    {"icon": "wallpaper", "title": "Фоны и темы чатов", "text": "Все фоны, цветовые темы оформления"},
    {"icon": "tick", "title": "Синяя галочка", "text": "Синяя галочка у имени (зелёная — у официальных, её выдаёт администратор) и цвета профиля"},
    {"icon": "limits", "title": "Больше возможностей", "text": "Файлы до 4 ГБ, 10 папок чатов, 10 закреплённых чатов"},
    {"icon": "icon", "title": "Значок приложения", "text": "Другие значки ICQ на главном экране"},
]
MONTH_MS = 30 * 86_400_000
# "Поддержка" opens a chat with this account (set ICQ_SUPPORT_UIN to the admin's own ID); the ICQ bot otherwise
SUPPORT_UIN = int(os.environ.get("ICQ_SUPPORT_UIN", "10000000") or "10000000")
KINDS = ("text", "sticker", "image", "file", "voice", "video_note", "poll")
GROUP_DEFAULTS = {
    "only_admins_add": False,   # only admins may add members
    "slow_mode": 0,             # seconds between messages for ordinary members
    "members_media": True,      # members may send photos / files / voice
    "reactions": True,          # reactions allowed
    "welcome": True,            # the bot greets new members
    "help_on_open": True,       # the bot shows the help card when the chat is opened
    "signatures": False,        # channel: show the author of a post
    "show_subscribers": False,  # channel: subscribers may see who else is subscribed
}
USERNAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{3,31}$")
ATT_KINDS = ("image", "file", "voice", "video_note")
# the server is a relay, not an archive: phones keep the history, the server forgets old messages
KEEP_DAYS = int(os.environ.get("ICQ_KEEP_DAYS", "14"))
TTL_CHOICES = (0, 3600, 86400, 2 * 86400, 7 * 86400, 30 * 86400)
DEFAULT_TTL = 2 * 86400  # everybody's messages vanish after 2 days unless the user turns it off
UIN_START = 10_000_000
BOT_UIN = UIN_START  # the official "ICQ Бот" account (people get UINs from 10000001)
SERVICE_UIN = 777000  # "ICQ" service account: login codes and new-device alerts (like Telegram's 777000)
CODE_TTL_MS = 10 * 60 * 1000
RESEND_MS = 30 * 1000
MAX_ATTEMPTS = 5
MAX_AVATAR = 3 * 1024 * 1024
MAX_GROUP = 200
REACTIONS = ("❤️", "👍", "😂", "😮", "😢", "🔥", "👎", "🙏")
_uin_lock = threading.Lock()
_rev_lock = threading.Lock()

os.makedirs(AVATAR_DIR, exist_ok=True)
os.makedirs(UPLOAD_DIR, exist_ok=True)
app = FastAPI(title="ICQ New server")
BLOB_DIRS = {"uploads": UPLOAD_DIR, "avatars": AVATAR_DIR}


def local_blob(kind: str, name: str) -> Optional[str]:
    """Path of a file on this server; in a cluster a missing copy is taken from another server first."""
    if not icqcluster.NAME_RE.match(name or "") or kind not in BLOB_DIRS:
        return None
    p = os.path.join(BLOB_DIRS[kind], name)
    if os.path.exists(p):
        return p
    if icqcluster.ENABLED and CLUSTER.fetch_file(kind, name, p):
        return p
    return None


@app.get("/.well-known/acme-challenge/{token}")
def acme_challenge(token: str):
    """Let's Encrypt in a cluster: the check may come to any server, the answer is kept in Redis (acme-hook.sh)."""
    if not icqcluster.ENABLED or CLUSTER.rs is None or not re.fullmatch(r"[A-Za-z0-9_\-]{10,200}", token):
        raise HTTPException(404, "Not Found")
    v = CLUSTER.rs.get(f"icq:acme:{token}")
    if not v:
        raise HTTPException(404, "Not Found")
    return Response(v, media_type="text/plain")


@app.get("/avatars/{name}")
async def avatar_file(name: str):
    p = await asyncio.to_thread(local_blob, "avatars", name)
    if not p:
        raise HTTPException(404, "Not Found")
    return FileResponse(p, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=86400"})


def _cluster_auth(request: Request) -> None:
    if not icqcluster.ENABLED or not icqcluster.SECRET or not secrets.compare_digest(
            request.headers.get("x-icq-cluster", ""), icqcluster.SECRET):
        raise HTTPException(403, "Forbidden")


@app.get("/internal/blob")
def internal_blob_get(kind: str, name: str, request: Request):
    """Another server of the cluster asks for a file (only this server's own copy, no further fetching)."""
    _cluster_auth(request)
    if kind not in BLOB_DIRS or not icqcluster.NAME_RE.match(name):
        raise HTTPException(400, "bad name")
    p = os.path.join(BLOB_DIRS[kind], name)
    if not os.path.exists(p):
        raise HTTPException(404, "Not Found")
    return FileResponse(p, media_type="application/octet-stream")


@app.put("/internal/blob")
async def internal_blob_put(kind: str, name: str, request: Request) -> dict:
    """Another server copies a new file here."""
    _cluster_auth(request)
    if kind not in BLOB_DIRS or not icqcluster.NAME_RE.match(name):
        raise HTTPException(400, "bad name")
    p = os.path.join(BLOB_DIRS[kind], name)
    tmp = p + ".part"
    with open(tmp, "wb") as f:
        async for chunk in request.stream():
            f.write(chunk)
    os.replace(tmp, p)
    return {"ok": True}


@app.delete("/internal/blob")
def internal_blob_delete(kind: str, name: str, request: Request) -> dict:
    _cluster_auth(request)
    if kind in BLOB_DIRS and icqcluster.NAME_RE.match(name):
        try:
            os.remove(os.path.join(BLOB_DIRS[kind], name))
        except OSError:
            pass
    return {"ok": True}



def _admin_password() -> str:
    pw = os.environ.get("ICQ_ADMIN_PASSWORD")
    if pw:
        return pw
    path = "admin_password.txt"
    if os.path.exists(path):
        return open(path).read().strip()
    pw = secrets.token_urlsafe(9)
    with open(path, "w") as f:
        f.write(pw)
    print(f"[ICQ] admin password generated: {pw}  (saved in {path})", flush=True)
    return pw


ADMIN_PASSWORD = _admin_password()


# --------------------------------------------------------------------------- DB
def conn() -> sqlite3.Connection:
    """SQLite file (one server) or the shared PostgreSQL of the cluster (ICQ_DB_URL) — see db.py."""
    return icqdb.connect(DB_PATH)


def _add_column(c: sqlite3.Connection, table: str, ddl: str) -> None:
    icqdb.add_column(c, table, ddl)


def init_db() -> None:
    icqdb.prepare()
    with conn() as c:
        icqdb.advisory_lock(c, 4242)  # two servers starting together do not change the schema at once
        c.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS users(
                uin INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                phone TEXT NOT NULL UNIQUE,
                phone_key TEXT NOT NULL,
                avatar_url TEXT,
                last_seen INTEGER NOT NULL DEFAULT 0,
                searchable INTEGER NOT NULL DEFAULT 1,
                show_online INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_users_key ON users(phone_key);
            CREATE TABLE IF NOT EXISTS tokens(
                token TEXT PRIMARY KEY,
                uin INTEGER NOT NULL,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS otp(
                phone TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                created INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                sent INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS contacts(
                owner INTEGER NOT NULL,
                contact INTEGER NOT NULL,
                PRIMARY KEY(owner, contact)
            );
            CREATE TABLE IF NOT EXISTS phonebook(
                owner INTEGER NOT NULL,
                key TEXT NOT NULL,
                PRIMARY KEY(owner, key)
            );
            CREATE INDEX IF NOT EXISTS idx_phonebook_key ON phonebook(key);
            CREATE TABLE IF NOT EXISTS messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                client_id TEXT NOT NULL,
                from_uin INTEGER NOT NULL,
                to_uin INTEGER NOT NULL,
                text TEXT NOT NULL,
                ts INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL DEFAULT 'text',
                att_id TEXT,
                att_name TEXT,
                att_size INTEGER NOT NULL DEFAULT 0,
                att_mime TEXT,
                att_dur INTEGER NOT NULL DEFAULT 0,
                UNIQUE(from_uin, client_id)
            );
            CREATE TABLE IF NOT EXISTS files(
                id TEXT PRIMARY KEY,
                owner INTEGER NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER NOT NULL,
                dur INTEGER NOT NULL DEFAULT 0,
                path TEXT NOT NULL,
                created INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_msg_to ON messages(to_uin, id);
            CREATE INDEX IF NOT EXISTS idx_msg_from ON messages(from_uin, id);

            CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS reactions(
                msg_id INTEGER NOT NULL,
                uin INTEGER NOT NULL,
                emoji TEXT NOT NULL,
                PRIMARY KEY(msg_id, uin)
            );
            CREATE TABLE IF NOT EXISTS groups(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                avatar_url TEXT,
                owner INTEGER NOT NULL,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS group_members(
                gid INTEGER NOT NULL,
                uin INTEGER NOT NULL,
                joined INTEGER NOT NULL,
                read_upto INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(gid, uin)
            );
            CREATE INDEX IF NOT EXISTS idx_gm_uin ON group_members(uin);
            CREATE TABLE IF NOT EXISTS blocks(
                owner INTEGER NOT NULL,
                blocked INTEGER NOT NULL,
                PRIMARY KEY(owner, blocked)
            );
            """
        )
        # v2 -> v3 migration (keeps users and history)
        _add_column(c, "users", "about TEXT NOT NULL DEFAULT ''")
        _add_column(c, "users", "banned INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "users", "created INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "messages", "group_id INTEGER")
        _add_column(c, "messages", "reply_to INTEGER")
        _add_column(c, "messages", "edited INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "messages", "deleted INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "messages", "fwd_name TEXT")
        _add_column(c, "messages", "rev INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "groups", "kind TEXT NOT NULL DEFAULT 'group'")
        _add_column(c, "groups", "about TEXT NOT NULL DEFAULT ''")
        _add_column(c, "groups", "public INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "group_members", "role TEXT NOT NULL DEFAULT 'member'")
        _add_column(c, "group_members", "muted_until INTEGER NOT NULL DEFAULT 0")
        c.execute(
            "UPDATE group_members SET role='owner' WHERE role<>'owner' AND EXISTS"
            " (SELECT 1 FROM groups g WHERE g.id=group_members.gid AND g.owner=group_members.uin)"
        )
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS group_bans(gid INTEGER NOT NULL, uin INTEGER NOT NULL, PRIMARY KEY(gid, uin));
            CREATE TABLE IF NOT EXISTS clears(id INTEGER PRIMARY KEY AUTOINCREMENT, uin INTEGER NOT NULL,
                peer INTEGER NOT NULL, upto INTEGER NOT NULL, rev INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_clears ON clears(uin, rev);
            CREATE TABLE IF NOT EXISTS push_tokens(token TEXT PRIMARY KEY, uin INTEGER NOT NULL, created INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_push_uin ON push_tokens(uin);
            """
        )
        _add_column(c, "users", "username TEXT")
        _add_column(c, "users", "verified INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "groups", "username TEXT")
        _add_column(c, "groups", "verified INTEGER NOT NULL DEFAULT 0")
        # privacy: who sees my photo / my phone number ('all' | 'contacts' | 'nobody')
        _add_column(c, "users", "avatar_privacy TEXT NOT NULL DEFAULT 'all'")
        _add_column(c, "users", "phone_privacy TEXT NOT NULL DEFAULT 'contacts'")
        # contacts: my own name for the person, whether I showed him my number, when added
        _add_column(c, "contacts", "alias TEXT")
        _add_column(c, "contacts", "share_phone INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "contacts", "created INTEGER NOT NULL DEFAULT 0")
        # profile extras (Telegram-like)
        _add_column(c, "users", "birthday TEXT")
        _add_column(c, "users", "channel_id INTEGER")
        _add_column(c, "users", "color INTEGER NOT NULL DEFAULT 0")
        # auto-delete timers
        _add_column(c, "messages", "expire_at INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "groups", "ttl INTEGER NOT NULL DEFAULT 0")
        # a private message that answers a publication (story): which one, and its picture
        _add_column(c, "messages", "story_id INTEGER")
        # voice / round video: has the recipient listened to it (blue dot until then, like Telegram)
        _add_column(c, "messages", "played INTEGER NOT NULL DEFAULT 0")
        # Premium: balance (whole manats, topped up by the admin), subscription end, account extras
        _add_column(c, "users", "balance INTEGER NOT NULL DEFAULT 0")
        # publications stay in the profile after their 24 h in the ring; reposts point to the original
        _add_column(c, "users", "pw_hash TEXT")
        # Fragment: @names on sale for Gold (the admin puts short / nice ones up, 1–3 letters included)
        c.execute(
            "CREATE TABLE IF NOT EXISTS fragment_lots(username TEXT PRIMARY KEY COLLATE NOCASE, price INTEGER NOT NULL,"
            " ts INTEGER NOT NULL, sold_to INTEGER, sold_ts INTEGER)"
        )
        c.execute(
            "CREATE TABLE IF NOT EXISTS user_usernames(username TEXT PRIMARY KEY COLLATE NOCASE, uin INTEGER NOT NULL, ts INTEGER NOT NULL)"
        )
        _add_column(c, "users", "premium_until INTEGER NOT NULL DEFAULT 0")
        _add_column(c, "users", "surname TEXT")
        _add_column(c, "users", "email TEXT")
        _add_column(c, "groups", "premium_until INTEGER NOT NULL DEFAULT 0")
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS balance_log(id INTEGER PRIMARY KEY AUTOINCREMENT, uin INTEGER NOT NULL,
                delta INTEGER NOT NULL, reason TEXT NOT NULL, ts INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_balance_uin ON balance_log(uin, ts);
            CREATE TABLE IF NOT EXISTS user_gifts(id INTEGER PRIMARY KEY AUTOINCREMENT, to_uin INTEGER NOT NULL,
                from_uin INTEGER NOT NULL, gift TEXT NOT NULL, text TEXT, anonymous INTEGER NOT NULL DEFAULT 0,
                ts INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_gifts_to ON user_gifts(to_uin, ts);
            """
        )
        _add_column(c, "messages", "story_att TEXT")
        # personal auto-delete default (applies to private chats without their own timer, and to new groups)
        _add_column(c, "users", f"default_ttl INTEGER NOT NULL DEFAULT {DEFAULT_TTL}")
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS chat_ttl(a INTEGER NOT NULL, b INTEGER NOT NULL, ttl INTEGER NOT NULL,
                PRIMARY KEY(a, b));
            CREATE TABLE IF NOT EXISTS qr_logins(code TEXT PRIMARY KEY, created INTEGER NOT NULL,
                token TEXT, uin INTEGER);
            CREATE TABLE IF NOT EXISTS link_cache(url TEXT PRIMARY KEY, data TEXT NOT NULL, ts INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS post_views(msg_id INTEGER NOT NULL, uin INTEGER NOT NULL, ts INTEGER NOT NULL,
                PRIMARY KEY(msg_id, uin));
            CREATE INDEX IF NOT EXISTS idx_views_ts ON post_views(ts);
            CREATE TABLE IF NOT EXISTS polls(msg_id INTEGER PRIMARY KEY, options TEXT NOT NULL,
                multiple INTEGER NOT NULL DEFAULT 0, anonymous INTEGER NOT NULL DEFAULT 1, closed INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS poll_votes(msg_id INTEGER NOT NULL, uin INTEGER NOT NULL, opt INTEGER NOT NULL,
                ts INTEGER NOT NULL, PRIMARY KEY(msg_id, uin, opt));
            CREATE TABLE IF NOT EXISTS group_log(id INTEGER PRIMARY KEY AUTOINCREMENT, gid INTEGER NOT NULL,
                uin INTEGER NOT NULL, actor INTEGER NOT NULL DEFAULT 0, action TEXT NOT NULL, ts INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_glog ON group_log(gid, ts);
            CREATE INDEX IF NOT EXISTS idx_msg_expire ON messages(expire_at);
            CREATE INDEX IF NOT EXISTS idx_msg_ts ON messages(ts);
            """
        )
        _add_column(c, "groups", "settings TEXT NOT NULL DEFAULT '{}'")
        _add_column(c, "groups", "bot_name TEXT NOT NULL DEFAULT 'ICQ Бот'")
        _add_column(c, "groups", "bot_avatar TEXT")
        _add_column(c, "tokens", "device TEXT NOT NULL DEFAULT ''")
        _add_column(c, "tokens", "last_used INTEGER NOT NULL DEFAULT 0")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(lower(username)) WHERE username IS NOT NULL")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_username ON groups(lower(username)) WHERE username IS NOT NULL")
        c.execute("CREATE INDEX IF NOT EXISTS idx_msg_rev ON messages(rev)")
        c.execute(
            "INSERT OR IGNORE INTO users(uin, name, phone, phone_key, about, verified, username, created, last_seen)"
            " VALUES(?, 'ICQ Бот', 'bot', 'bot', 'Официальный бот ICQ New. Напишите /help', 1, 'icqbot', ?, ?)",
            (BOT_UIN, int(time.time() * 1000), int(time.time() * 1000)),
        )
        if not c.execute("SELECT 1 FROM groups WHERE username=?", ("icqcommunity",)).fetchone():
            now0 = int(time.time() * 1000)
            cur0 = c.execute(
                "INSERT INTO groups(name, owner, created, kind, about, public, verified, username)"
                " VALUES('ICQ Community', ?, ?, 'group', ?, 1, 1, 'icqcommunity')",
                (BOT_UIN, now0, "Официальный общий чат ICQ New: новости, вопросы, идеи. Будьте вежливы 🙂"),
            )
            c.execute("INSERT OR IGNORE INTO group_members(gid, uin, joined, role) VALUES(?,?,?,'owner')",
                      (cur0.lastrowid, BOT_UIN, now0))
        c.execute(
            "INSERT OR IGNORE INTO users(uin, name, phone, phone_key, about, verified, username, created, last_seen)"
            " VALUES(?, 'ICQ', 'service', 'service', 'Служебные уведомления: коды входа и новые устройства', 1, 'icq', ?, ?)",
            (SERVICE_UIN, int(time.time() * 1000), int(time.time() * 1000)),
        )
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS stories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uin INTEGER NOT NULL,
                att_id TEXT NOT NULL,
                text TEXT NOT NULL DEFAULT '',
                ts INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_stories_uin ON stories(uin, ts);
            CREATE TABLE IF NOT EXISTS story_views(
                story INTEGER NOT NULL,
                uin INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                PRIMARY KEY(story, uin)
            );
            """
        )
        _add_column(c, "story_views", "reaction TEXT")
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS story_comments(
                id INTEGER PRIMARY KEY AUTOINCREMENT, story INTEGER NOT NULL, uin INTEGER NOT NULL,
                text TEXT NOT NULL, ts INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_story_comments ON story_comments(story, id);
            """
        )
        c.execute("CREATE INDEX IF NOT EXISTS idx_msg_group ON messages(group_id, id)")
        _add_column(c, "stories", "repost_of INTEGER")
        _add_column(c, "stories", "repost_uin INTEGER")
        _add_column(c, "qr_logins", "device TEXT")
        # admin app: who sends the SMS with the code (by hand or automatically from a phone)
        for col in ("claimed_by INTEGER", "claimed_at INTEGER NOT NULL DEFAULT 0", "sent_by INTEGER",
                    "sent_at INTEGER NOT NULL DEFAULT 0", "sms_error TEXT"):
            _add_column(c, "otp", col)
        c.execute("CREATE TABLE IF NOT EXISTS server_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, ip TEXT NOT NULL,"
                  " status TEXT NOT NULL, log TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS admins(id INTEGER PRIMARY KEY AUTOINCREMENT, login TEXT NOT NULL, pw_hash TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'admin', perms TEXT NOT NULL DEFAULT '[]', enabled INTEGER NOT NULL DEFAULT 1,
                created INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0, device TEXT, auto_sms INTEGER NOT NULL DEFAULT 0,
                sms_sent INTEGER NOT NULL DEFAULT 0);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_admins_login ON admins(lower(login));
            CREATE TABLE IF NOT EXISTS admin_tokens(token TEXT PRIMARY KEY, admin_id INTEGER NOT NULL, created INTEGER NOT NULL,
                last_used INTEGER NOT NULL DEFAULT 0, device TEXT);
            CREATE TABLE IF NOT EXISTS admin_log(id INTEGER PRIMARY KEY AUTOINCREMENT, admin_id INTEGER NOT NULL,
                login TEXT NOT NULL, action TEXT NOT NULL, ts INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_admin_log ON admin_log(ts);
            """
        )
        c.execute("UPDATE messages SET rev=id WHERE rev=0")
        c.execute(
            "INSERT OR IGNORE INTO meta(k, v) VALUES('rev', (SELECT COALESCE(MAX(rev), 0) FROM messages))"
        )


init_db()


def now_ms() -> int:
    return int(time.time() * 1000)


def next_rev(c: sqlite3.Connection) -> int:
    with _rev_lock:
        c.execute("UPDATE meta SET v=v+1 WHERE k='rev'")
        return c.execute("SELECT v FROM meta WHERE k='rev'").fetchone()["v"]


def digits(s: Optional[str]) -> str:
    return "".join(ch for ch in (s or "") if ch.isdigit())


def norm_phone(raw: Optional[str]) -> str:
    """Digits only, international form (local BY/RU '8...' prefixes are converted)."""
    d = digits(raw)
    if d.startswith("00"):
        d = d[2:]
    if len(d) == 11 and d.startswith("80"):
        d = "375" + d[2:]
    elif len(d) == 11 and d.startswith("8"):
        d = "7" + d[1:]
    return d


def phone_key(phone: str) -> str:
    return phone[-8:]


# ------------------------------------------------------------------ connections
class Manager:
    def __init__(self) -> None:
        self.conns: Dict[int, List[WebSocket]] = {}
        # websockets whose app is in the background (connected by the service, user not looking)
        self.idle: set = set()
        self.ws_tokens: dict = {}

    def add(self, uin: int, ws: WebSocket) -> bool:
        lst = self.conns.setdefault(uin, [])
        first = not lst
        lst.append(ws)
        self.changed(uin)
        return first

    def remove(self, uin: int, ws: WebSocket) -> bool:
        lst = self.conns.get(uin, [])
        self.idle.discard(id(ws))
        if ws in lst:
            lst.remove(ws)
        last = not lst
        if last:
            self.conns.pop(uin, None)
        self.changed(uin)
        return last

    def set_idle(self, uin: int, ws: WebSocket, idle: bool) -> None:
        if idle:
            self.idle.add(id(ws))
        else:
            self.idle.discard(id(ws))
        self.changed(uin)

    def _local(self, uin: int) -> Tuple[int, int]:
        lst = self.conns.get(uin, [])
        return sum(1 for ws in lst if id(ws) not in self.idle), len(lst)

    def changed(self, uin: int) -> None:
        """Tells the other servers of the cluster how [uin] is connected to this one."""
        a, t = self._local(uin)
        CLUSTER.presence(uin, a, t)

    def connected(self, uin: int) -> bool:
        return uin in self.conns or CLUSTER.remote_counts(uin)[1] > 0

    def online(self, uin: int) -> bool:
        """'Online' = the app is open on at least one device (not just a background connection), on any server."""
        return any(id(ws) not in self.idle for ws in self.conns.get(uin, [])) or CLUSTER.remote_counts(uin)[0] > 0

    def online_count(self) -> int:
        return len(set(self.conns) | CLUSTER.remote_uins())

    async def deliver(self, uin: int, payload, skip: Optional[WebSocket] = None) -> None:
        """Only to this server's connections."""
        text = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
        for ws in list(self.conns.get(uin, [])):
            if ws is skip:
                continue
            try:
                await ws.send_text(text)
            except Exception:
                self.remove(uin, ws)

    async def send(self, uin: int, payload: dict, skip: Optional[WebSocket] = None) -> None:
        await self.deliver(uin, payload, skip)
        if CLUSTER.remote_counts(uin)[1] > 0:
            CLUSTER.publish({"t": "send", "u": [uin], "p": payload})

    async def send_many(self, uins, payload: dict, skip: Optional[WebSocket] = None) -> None:
        uins = set(uins)
        text = json.dumps(payload, ensure_ascii=False)
        for u in uins:
            await self.deliver(u, text, skip)
        far = [u for u in uins if CLUSTER.remote_counts(u)[1] > 0]
        if far:
            CLUSTER.publish({"t": "send", "u": far, "p": payload})

    async def kick(self, uin: int, everywhere: bool = True) -> None:
        for ws in list(self.conns.get(uin, [])):
            try:
                await ws.close(code=4403)
            except Exception:
                pass
        if everywhere:
            CLUSTER.publish({"t": "kick", "u": uin})

    async def kick_tokens(self, uin: int, tokens: List[str], everywhere: bool = True) -> None:
        for w in list(self.conns.get(uin, [])):
            if self.ws_tokens.get(id(w)) in tokens:
                try:
                    await w.close(code=4401)
                except Exception:
                    pass
        if everywhere:
            CLUSTER.publish({"t": "kicktok", "u": uin, "tokens": tokens})

    def token_online(self, uin: int, token: str) -> bool:
        return any(self.ws_tokens.get(id(w)) == token for w in self.conns.get(uin, [])) or CLUSTER.token_online(token)


manager = Manager()


CURRENT_VIEWER: contextvars.ContextVar[int] = contextvars.ContextVar("viewer", default=0)
PRIVACY_MODES = ("all", "contacts", "nobody")


def _col(row: sqlite3.Row, name: str, default=None):
    return row[name] if name in row.keys() else default


def has_contact(owner: int, other: int, c: Optional[sqlite3.Connection] = None) -> bool:
    """True if [owner] added [other] to his contacts."""
    if not owner or not other:
        return False
    if c is not None:
        return c.execute("SELECT 1 FROM contacts WHERE owner=? AND contact=? AND created>0", (owner, other)).fetchone() is not None
    with conn() as c2:
        return c2.execute("SELECT 1 FROM contacts WHERE owner=? AND contact=? AND created>0", (owner, other)).fetchone() is not None


def visible_avatar(row: sqlite3.Row, viewer: int, c: Optional[sqlite3.Connection] = None) -> Optional[str]:
    url = row["avatar_url"]
    mode = _col(row, "avatar_privacy", "all") or "all"
    if not url or mode == "all" or viewer == row["uin"]:
        return url
    if mode == "contacts" and has_contact(row["uin"], viewer, c):
        return url
    return None


def visible_phone(row: sqlite3.Row, viewer: int, c: Optional[sqlite3.Connection] = None) -> str:
    phone = _col(row, "phone", "") or ""
    if not phone or phone in ("bot", "service") or phone.startswith("mail:") or row["uin"] in (BOT_UIN, SERVICE_UIN):
        return ""
    if viewer == row["uin"]:
        return phone
    mode = _col(row, "phone_privacy", "contacts") or "contacts"
    if mode == "all":
        return phone
    q = "SELECT share_phone FROM contacts WHERE owner=? AND contact=?"
    if c is not None:
        k = c.execute(q, (row["uin"], viewer)).fetchone()
    else:
        with conn() as c2:
            k = c2.execute(q, (row["uin"], viewer)).fetchone()
    if k and (k["share_phone"] or mode == "contacts"):
        return phone
    return ""


def user_json(row: sqlite3.Row, full: bool = False, viewer: Optional[int] = None, with_phone: bool = False,
              c: Optional[sqlite3.Connection] = None) -> dict:
    """Public view of a user. If the user hid his online status, others see offline / no last seen."""
    visible = full or bool(row["show_online"])
    v = CURRENT_VIEWER.get() if viewer is None else viewer
    d = {
        "uin": row["uin"],
        "name": row["name"],
        "avatar_url": row["avatar_url"] if full else visible_avatar(row, v, c),
        "about": row["about"] or "",
        # online is always shown; a hidden last-seen time is shown as "recently"
        "online": False if row["uin"] in (BOT_UIN, SERVICE_UIN) else manager.online(row["uin"]),
        "bot": row["uin"] == BOT_UIN,
        "service": row["uin"] == SERVICE_UIN,
        "last_seen": row["last_seen"] if visible else 0,
        "username": row["username"],
        "verified": bool(row["verified"]),
        "premium": (_col(row, "premium_until", 0) or 0) > now_ms(),
        "surname": _col(row, "surname") or "",
        "usernames": [r["username"] for r in c.execute("SELECT username FROM user_usernames WHERE uin=? ORDER BY ts",
                                                        (row["uin"],))] if c is not None else [],
        "color": _col(row, "color", 0) or 0,
        "birthday": _col(row, "birthday") or "",
    }
    ch = _col(row, "channel_id")
    if ch and (with_phone or full):
        try:
            if c is not None:
                g = c.execute("SELECT id, name, avatar_url FROM groups WHERE id=?", (ch,)).fetchone()
            else:
                with conn() as c2:
                    g = c2.execute("SELECT id, name, avatar_url FROM groups WHERE id=?", (ch,)).fetchone()
            if g:
                d["channel"] = {"id": g["id"], "name": g["name"], "avatar_url": g["avatar_url"]}
        except sqlite3.Error:
            pass
    if with_phone and not full:
        d["phone"] = visible_phone(row, v, c)
    return d


def me_json(row: sqlite3.Row, c: Optional[sqlite3.Connection] = None) -> dict:
    d = user_json(row, full=True, c=c)
    d["avatar_privacy"] = _col(row, "avatar_privacy", "all") or "all"
    d["phone_privacy"] = _col(row, "phone_privacy", "contacts") or "contacts"
    d["searchable"] = bool(row["searchable"])
    d["show_online"] = bool(row["show_online"])
    d["phone"] = "" if (row["phone"] or "").startswith("mail:") else row["phone"]
    d["has_password"] = bool(_col(row, "pw_hash"))
    d["balance"] = _col(row, "balance", 0) or 0
    d["premium_until"] = _col(row, "premium_until", 0) or 0
    d["surname"] = _col(row, "surname") or ""
    d["email"] = _col(row, "email") or ""
    return d


def reactions_of(c: sqlite3.Connection, msg_id: int) -> list:
    rows = c.execute("SELECT uin, emoji FROM reactions WHERE msg_id=? ORDER BY "
                     + ("ctid" if icqdb.IS_PG else "rowid"), (msg_id,)).fetchall()
    return [{"uin": r["uin"], "emoji": r["emoji"]} for r in rows]


def poll_json(c: sqlite3.Connection, mid: int, viewer: int = 0) -> Optional[dict]:
    p = c.execute("SELECT * FROM polls WHERE msg_id=?", (mid,)).fetchone()
    if not p:
        return None
    opts = json.loads(p["options"])
    counts = [0] * len(opts)
    for r in c.execute("SELECT opt, COUNT(*) AS n FROM poll_votes WHERE msg_id=? GROUP BY opt", (mid,)):
        if 0 <= r["opt"] < len(counts):
            counts[r["opt"]] = r["n"]
    voters = c.execute("SELECT COUNT(DISTINCT uin) AS n FROM poll_votes WHERE msg_id=?", (mid,)).fetchone()["n"]
    d = {
        "options": [{"text": t, "votes": counts[i]} for i, t in enumerate(opts)],
        "voters": voters, "multiple": bool(p["multiple"]), "anonymous": bool(p["anonymous"]),
        "closed": bool(p["closed"]),
    }
    if viewer:
        d["my"] = [r["opt"] for r in c.execute("SELECT opt FROM poll_votes WHERE msg_id=? AND uin=?", (mid, viewer))]
    return d


def views_of(c: sqlite3.Connection, row: sqlite3.Row) -> int:
    if not row["group_id"]:
        return 0
    return c.execute("SELECT COUNT(*) AS n FROM post_views WHERE msg_id=?", (row["id"],)).fetchone()["n"]


def log_group(c: sqlite3.Connection, gid: int, uin: int, action: str, actor: int = 0) -> None:
    """Channel / group 'recent actions' log (join, leave, kick, ban)."""
    c.execute("INSERT INTO group_log(gid, uin, actor, action, ts) VALUES(?,?,?,?,?)", (gid, uin, actor, action, now_ms()))


def msg_json(c: sqlite3.Connection, row: sqlite3.Row, viewer: int = 0) -> dict:
    d = _msg_json(c, row)
    if _col(row, "story_id"):
        d["story"] = {"id": row["story_id"], "att_id": _col(row, "story_att")}
    d["views"] = views_of(c, row)
    if row["kind"] == "poll":
        d["poll"] = poll_json(c, row["id"], viewer)
    return d


def _msg_json(c: sqlite3.Connection, row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "client_id": row["client_id"],
        "from_uin": row["from_uin"],
        "to_uin": row["to_uin"],
        "group_id": row["group_id"],
        "text": row["text"],
        "ts": row["ts"],
        "read": bool(row["is_read"]),
        "kind": row["kind"],
        "att_id": row["att_id"],
        "att_name": row["att_name"],
        "att_size": row["att_size"],
        "att_mime": row["att_mime"],
        "att_dur": row["att_dur"],
        "reply_to": row["reply_to"],
        "edited": bool(row["edited"]),
        "deleted": bool(row["deleted"]),
        "played": bool(_col(row, "played", 0)),
        "fwd_name": row["fwd_name"],
        "rev": row["rev"],
        "reactions": reactions_of(c, row["id"]),
        "expire_at": _col(row, "expire_at", 0) or 0,
    }


def related_uins(c: sqlite3.Connection, uin: int) -> List[int]:
    rows = c.execute(
        "SELECT owner AS u FROM contacts WHERE contact=? UNION SELECT contact AS u FROM contacts WHERE owner=?"
        " UNION SELECT m2.uin AS u FROM group_members m1 JOIN group_members m2 ON m1.gid=m2.gid"
        " WHERE m1.uin=? AND m2.uin<>?",
        (uin, uin, uin, uin),
    ).fetchall()
    return [r["u"] for r in rows]


def group_members(c: sqlite3.Connection, gid: int) -> List[int]:
    return [r["uin"] for r in c.execute("SELECT uin FROM group_members WHERE gid=?", (gid,)).fetchall()]


def is_member(c: sqlite3.Connection, gid: int, uin: int) -> bool:
    return c.execute("SELECT 1 FROM group_members WHERE gid=? AND uin=?", (gid, uin)).fetchone() is not None


def is_blocked(c: sqlite3.Connection, owner: int, other: int) -> bool:
    return c.execute("SELECT 1 FROM blocks WHERE owner=? AND blocked=?", (owner, other)).fetchone() is not None


def message_targets(c: sqlite3.Connection, row: sqlite3.Row) -> List[int]:
    if row["group_id"]:
        return group_members(c, row["group_id"])
    return [row["from_uin"], row["to_uin"]]


# ------------------------------------------------------------------------- auth
def uin_from_token(token: str) -> Optional[int]:
    if not token:
        return None
    with conn() as c:
        row = c.execute(
            "SELECT t.uin FROM tokens t JOIN users u ON u.uin=t.uin WHERE t.token=? AND u.banned=0", (token,)
        ).fetchone()
    return row["uin"] if row else None


def require_uin(authorization: Optional[str]) -> int:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "Требуется авторизация")
    uin = uin_from_token(authorization[7:].strip())
    if uin is None:
        raise HTTPException(401, "Сессия недействительна, войдите снова")
    CURRENT_VIEWER.set(uin)
    return uin


class CodeRequestIn(BaseModel):
    phone: str = ""
    email: str = ""


class VerifyIn(BaseModel):
    phone: str = ""
    email: str = ""
    code: str
    name: Optional[str] = None
    device: Optional[str] = None


class ContactIn(BaseModel):
    uin: Optional[int] = None
    phone: Optional[str] = None
    query: Optional[str] = None  # ID or @username
    name: Optional[str] = None  # my own name for this contact
    share_phone: bool = False


class SyncIn(BaseModel):
    keys: List[str]


class ProfileIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    about: Optional[str] = Field(default=None, max_length=140)
    birthday: Optional[str] = None  # "DD.MM" or "DD.MM.YYYY"; "" clears
    channel_id: Optional[int] = None  # 0 clears
    color: Optional[int] = None  # 0..8 (colours above 4 need Premium)
    surname: Optional[str] = Field(default=None, max_length=40)
    email: Optional[str] = Field(default=None, max_length=80)


class BlockIn(BaseModel):
    uin: int
    block: bool = True


class GroupCreateIn(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    members: List[int] = []
    kind: str = "group"
    about: Optional[str] = None
    public: bool = False


class GroupEditIn(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    about: Optional[str] = None
    public: Optional[bool] = None
    username: Optional[str] = None


class GroupSettingsIn(BaseModel):
    settings: dict = {}
    bot_name: Optional[str] = None


class ClearIn(BaseModel):
    to: Optional[int] = None
    group: Optional[int] = None
    for_all: bool = True


class UsernameIn(BaseModel):
    username: str = ""


class AdminVerifyIn(BaseModel):
    kind: str = "user"
    id: int
    on: bool = True


class ModerateIn(BaseModel):
    uin: int
    action: str
    seconds: int = 0


class PushTokenIn(BaseModel):
    token: str = Field(min_length=10, max_length=4096)


class GroupAddIn(BaseModel):
    members: List[int]


def notify_admin(phone: str, code: str) -> None:
    print(f"[OTP] +{phone}: {code}", flush=True)
    token = os.environ.get("ICQ_TG_TOKEN")
    chat = os.environ.get("ICQ_TG_CHAT")
    if not token or not chat:
        return

    def run() -> None:
        try:
            data = urllib.parse.urlencode(
                {"chat_id": chat, "text": f"ICQ: код для +{phone}\n{code}\n\nОтправьте его SMS со своего номера."}
            ).encode()
            urllib.request.urlopen(f"https://api.telegram.org/bot{token}/sendMessage", data, timeout=10)
        except Exception:
            pass

    threading.Thread(target=run, daemon=True).start()


# ------------------------------------------------------------------ FCM push
# Firebase Cloud Messaging (HTTP v1). Put the service-account JSON from Firebase console
# (Project settings -> Service accounts -> Generate new private key) to ICQ_FCM_JSON.
_fcm_lock = threading.Lock()
_fcm_state: dict = {"creds": None, "project": None}


def _fcm_auth():
    path = os.environ.get("ICQ_FCM_JSON")
    if not path or not os.path.exists(path):
        return None
    try:
        from google.auth.transport.requests import Request as GoogleRequest
        from google.oauth2 import service_account
    except ImportError:
        return None
    with _fcm_lock:
        if _fcm_state["creds"] is None:
            _fcm_state["creds"] = service_account.Credentials.from_service_account_file(
                path, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
            )
            with open(path) as f:
                _fcm_state["project"] = json.load(f)["project_id"]
        creds = _fcm_state["creds"]
        if not creds.valid:
            creds.refresh(GoogleRequest())
        return creds.token, _fcm_state["project"]


def push_data(uin: int, data: dict) -> None:
    """Sends a data-only high-priority push to every device of the user (in a thread)."""
    if not os.environ.get("ICQ_FCM_JSON"):
        return
    with conn() as c:
        tokens = [r["token"] for r in c.execute("SELECT token FROM push_tokens WHERE uin=?", (uin,)).fetchall()]
    if not tokens:
        return
    payload = {k: str(v) for k, v in data.items() if v is not None}

    def run() -> None:
        try:
            auth = _fcm_auth()
        except Exception as e:
            print(f"[fcm] auth failed: {e!r}", flush=True)
            return
        if not auth:
            return
        access, project = auth
        for t in tokens:
            body = json.dumps(
                {"message": {"token": t, "data": payload, "android": {"priority": "high", "ttl": "86400s"}}}
            ).encode()
            req = urllib.request.Request(
                f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
                data=body,
                headers={"Authorization": f"Bearer {access}", "Content-Type": "application/json"},
            )
            try:
                urllib.request.urlopen(req, timeout=10).read()
            except urllib.error.HTTPError as e:
                if e.code in (400, 404):  # token no longer valid
                    with conn() as c:
                        c.execute("DELETE FROM push_tokens WHERE token=?", (t,))
                else:
                    print(f"[fcm] {e.code}", flush=True)
            except Exception as e:
                print(f"[fcm] {e!r}", flush=True)

    threading.Thread(target=run, daemon=True).start()


def preview_text(kind: str, text: str, att_name: Optional[str]) -> str:
    return {
        "image": "📷 Фото",
        "file": "📎 " + (att_name or "Файл"),
        "voice": "🎤 Голосовое сообщение",
        "sticker": (text or "") + " Стикер",
    }.get(kind, text or "")[:200]


def push_message(c: sqlite3.Connection, row: sqlite3.Row, targets: List[int]) -> None:
    offline = [t for t in set(targets) if t != row["from_uin"] and not manager.connected(t)]
    if not offline:
        return
    sender = uname(c, row["from_uin"])
    body = preview_text(row["kind"], row["text"], row["att_name"])
    if row["group_id"]:
        g = c.execute("SELECT name, kind FROM groups WHERE id=?", (row["group_id"],)).fetchone()
        title = g["name"] if g else "Группа"
        if g and g["kind"] != "channel":
            body = f"{sender}: {body}"
        peer = -row["group_id"]
    else:
        title, peer = sender, None
    for t in offline:
        push_data(t, {"type": "msg", "peer": peer if peer is not None else row["from_uin"], "title": title, "body": body})


def push_call(to: int, sender: int, name: str, video: bool) -> None:
    push_data(to, {"type": "call", "peer": sender, "title": name, "video": "1" if video else "0"})


@app.post("/api/push/token")
def save_push_token(body: PushTokenIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        c.execute("INSERT OR REPLACE INTO push_tokens(token, uin, created) VALUES(?,?,?)", (body.token, uin, now_ms()))
    return {"ok": True}


@app.delete("/api/push/token")
def drop_push_token(token: str = Query(""), authorization: Optional[str] = Header(None)) -> dict:
    require_uin(authorization)
    with conn() as c:
        c.execute("DELETE FROM push_tokens WHERE token=?", (token,))
    return {"ok": True}


@app.get("/api/ping")
def ping() -> dict:
    return {"service": "ICQ New server", "version": 5, "online": manager.online_count()}


WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
APK_PATH = os.environ.get("ICQ_APK", os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk", "icq-new.apk"))
from apkdelta import Deltas  # noqa: E402  (delta updates: apk/history + apk/patches)
import apkdelta  # noqa: E402
DELTAS = Deltas(APK_PATH)


def _page(name: str) -> HTMLResponse:
    with open(os.path.join(WEB_DIR, name), encoding="utf-8") as f:
        return HTMLResponse(f.read(), headers={"Cache-Control": "no-cache"})


@app.get("/", response_class=HTMLResponse)
def root(request: Request):
    """Landing page (icq-new.ru): what ICQ New is + download button."""
    if "application/json" in (request.headers.get("accept") or ""):
        return JSONResponse(ping())
    host = (request.headers.get("host") or "").split(":")[0].lower()
    # icq-new.online = the app + web version; icq-new.ru (and the bare IP) = the website
    if host.endswith(os.environ.get("ICQ_APP_DOMAIN", "icq-new.online")):
        return _page("web.html")
    return _page("index.html")


@app.get("/web", response_class=HTMLResponse)
def web_client():
    """Web version (icq-new.online): log in by scanning a QR code with the phone."""
    return _page("web.html")


def _profile_page(title: str, sub: str, about: str, avatar: Optional[str], deep: str) -> HTMLResponse:
    e = html.escape
    img = f'<img class="av" src="{e(avatar)}" alt="">' if avatar else '<img class="av" src="/logo.svg" alt="" style="background:#000">'
    page = f"""<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{e(title)} — ICQ New</title>
<meta property="og:title" content="{e(title)}"><meta property="og:description" content="{e(sub + (' · ' + about if about else ''))}">
<meta property="og:site_name" content="ICQ New">{f'<meta property="og:image" content="{e(avatar)}">' if avatar else ''}
<link rel="icon" href="/logo.svg"><style>
body{{margin:0;font-family:system-ui,sans-serif;background:#0b0d0c;color:#e9f1ec;display:flex;min-height:100vh;align-items:center;justify-content:center}}
.c{{background:#141816;border:1px solid #1f2723;border-radius:26px;padding:34px 28px;text-align:center;max-width:360px;width:90%}}
.av{{width:120px;height:120px;border-radius:50%;object-fit:cover}} h1{{margin:14px 0 4px;font-size:24px}} .s{{color:#93a39a}}
p{{color:#c9d4ce}} a.b{{display:block;margin-top:14px;padding:13px;border-radius:14px;font-weight:700;text-decoration:none}}
.g{{background:linear-gradient(135deg,#22FF01,#00CB66);color:#04140a}} .o{{background:#1d2420;color:#e9f1ec}}
</style></head><body><div class="c">{img}<h1>{e(title)}</h1><div class="s">{e(sub)}</div><p>{e(about)}</p>
<a class="b g" href="{e(deep)}">Открыть в ICQ New</a><a class="b o" href="https://icq-new.ru">Скачать приложение</a><a class="b o" href="https://icq-new.online">Веб-версия</a></div></body></html>"""
    return HTMLResponse(page)


@app.get("/@{username}", response_class=HTMLResponse)
def public_profile(username: str):
    with conn() as c:
        u = c.execute("SELECT * FROM users WHERE lower(username)=lower(?) AND banned=0", (username,)).fetchone()
        if u:
            return _profile_page(u["name"], "@" + u["username"], u["about"] or "",
                                 visible_avatar(u, 0, c), f"icqnew://open?u={u['uin']}")
        g = c.execute("SELECT * FROM groups WHERE lower(username)=lower(?)", (username,)).fetchone()
    if not g:
        raise HTTPException(404, "Не найдено")
    return public_group(g["id"])


@app.get("/g/{gid}", response_class=HTMLResponse)
def public_group(gid: int):
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if not g or not (g["public"] or g["username"]):
            raise HTTPException(404, "Не найдено")
        n = c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (gid,)).fetchone()["n"]
    sub = f"{'канал' if g['kind'] == 'channel' else 'группа'} · {n} {'подписчиков' if g['kind'] == 'channel' else 'участников'}"
    return _profile_page(g["name"], sub, g["about"] or "", g["avatar_url"], f"icqnew://open?g={gid}")


@app.get("/api/app/version")
def app_version() -> dict:
    """Latest Android version (apk/version.json next to the APK): the app shows "update" at the bottom."""
    path = os.path.join(os.path.dirname(APK_PATH), "version.json")
    if not os.path.exists(APK_PATH):  # nothing to download yet
        return {"code": 0, "name": "", "notes": "", "url": "/download"}
    try:
        with open(path, encoding="utf-8") as f:
            v = json.load(f)
    except (OSError, ValueError):
        return {"code": 0, "name": "", "notes": "", "url": "/download"}
    return {
        "code": int(v.get("code") or 0), "name": str(v.get("name") or ""),
        "notes": str(v.get("notes") or ""), "url": "/download",
        "size": os.path.getsize(APK_PATH) if os.path.exists(APK_PATH) else 0,
        "sha256": DELTAS.sha(),
    }


@app.get("/api/app/patch/{old_code}")
def app_patch(old_code: int):
    """Only the difference between the phone's version and the new one (much smaller than the APK)."""
    cur = DELTAS.current_code()
    p = DELTAS.patch_path(old_code, cur)
    if not os.path.exists(p):
        raise HTTPException(404, "Патча нет — скачайте APK целиком")
    return FileResponse(p, media_type="application/octet-stream", filename=os.path.basename(p))


@app.get("/download")
def download_apk():
    if not os.path.exists(APK_PATH):
        raise HTTPException(404, "APK ещё не загружен на сервер")
    return FileResponse(APK_PATH, media_type="application/vnd.android.package-archive", filename="ICQ-New.apk")


# ---------------------------------------------------------- phone code by IP
DIAL_CODES = {
    "TM": "993", "BY": "375", "RU": "7", "KZ": "7", "UZ": "998", "TJ": "992", "KG": "996", "AZ": "994",
    "AM": "374", "GE": "995", "UA": "380", "MD": "373", "TR": "90", "IR": "98", "AF": "93", "PK": "92",
    "IN": "91", "CN": "86", "HK": "852", "MO": "853", "TW": "886", "JP": "81", "KR": "82", "MN": "976",
    "VN": "84", "TH": "66", "MY": "60", "SG": "65", "ID": "62", "PH": "63", "BD": "880", "LK": "94",
    "NP": "977", "AE": "971", "SA": "966", "QA": "974", "KW": "965", "BH": "973", "OM": "968", "IQ": "964",
    "SY": "963", "JO": "962", "LB": "961", "IL": "972", "EG": "20", "MA": "212", "DZ": "213", "TN": "216",
    "LY": "218", "NG": "234", "KE": "254", "ZA": "27", "ET": "251", "GH": "233",
    "US": "1", "CA": "1", "MX": "52", "BR": "55", "AR": "54", "CL": "56", "CO": "57", "PE": "51", "VE": "58",
    "GB": "44", "IE": "353", "DE": "49", "FR": "33", "IT": "39", "ES": "34", "PT": "351", "NL": "31",
    "BE": "32", "LU": "352", "CH": "41", "AT": "43", "PL": "48", "CZ": "420", "SK": "421", "HU": "36",
    "RO": "40", "BG": "359", "RS": "381", "HR": "385", "SI": "386", "BA": "387", "MK": "389", "AL": "355",
    "ME": "382", "GR": "30", "CY": "357", "LT": "370", "LV": "371", "EE": "372", "FI": "358", "SE": "46",
    "NO": "47", "DK": "45", "IS": "354", "AU": "61", "NZ": "64",
}
_geo_cache: Dict[str, str] = {}


def _client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for") or request.headers.get("x-real-ip") or ""
    ip = fwd.split(",")[0].strip()
    return ip or (request.client.host if request.client else "")


def _country_for_ip(ip: str) -> str:
    if not ip:
        return ""
    if ip in _geo_cache:
        return _geo_cache[ip]
    try:
        import ipaddress
        if ipaddress.ip_address(ip).is_private or ipaddress.ip_address(ip).is_loopback:
            return ""
    except ValueError:
        return ""
    cc = ""
    import urllib.request
    for url in (f"http://ip-api.com/json/{ip}?fields=status,countryCode", f"https://ipwho.is/{ip}?fields=country_code"):
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                j = json.loads(r.read().decode("utf-8", "ignore"))
            cc = (j.get("countryCode") or j.get("country_code") or "").upper()
            if cc:
                break
        except Exception:  # noqa: BLE001
            continue
    if cc:
        if len(_geo_cache) > 5000:
            _geo_cache.clear()
        _geo_cache[ip] = cc
    return cc


@app.get("/api/geo")
async def geo(request: Request, sim: str = Query("")) -> dict:
    """Country + phone dial code for the login screen (by the caller's IP, else by the SIM card country)."""
    cc = await asyncio.to_thread(_country_for_ip, _client_ip(request))
    source = "ip"
    if cc not in DIAL_CODES:
        cc, source = sim.strip().upper()[:2], "sim"
    if cc not in DIAL_CODES:
        return {"country": "", "dial": "", "source": "none"}
    return {"country": cc, "dial": "+" + DIAL_CODES[cc], "source": source}


@app.post("/api/auth/request")
async def request_code(body: CodeRequestIn) -> dict:
    email = norm_email(body.email)
    if email:
        return await request_email_code(email)
    phone = norm_phone(body.phone)
    if not (9 <= len(phone) <= 15):
        raise HTTPException(400, "Введите номер телефона в международном формате")
    now = now_ms()
    with conn() as c:
        u = c.execute("SELECT banned FROM users WHERE phone=?", (phone,)).fetchone()
        if u is not None and u["banned"]:
            raise HTTPException(403, "Аккаунт заблокирован администратором")
        row = c.execute("SELECT created FROM otp WHERE phone=?", (phone,)).fetchone()
        if row and now - row["created"] < RESEND_MS:
            wait = (RESEND_MS - (now - row["created"])) // 1000 + 1
            raise HTTPException(429, f"Подождите {wait} с и запросите код снова")
        code = f"{secrets.randbelow(1_000_000):06d}"
        c.execute(
            "INSERT OR REPLACE INTO otp(phone, code, created, attempts, sent, claimed_by, claimed_at, sent_by, sent_at, sms_error)"
            " VALUES(?,?,?,0,0,NULL,0,NULL,0,NULL)",
            (phone, code, now),
        )
    notify_admin(phone, code)
    in_app = False
    with conn() as c:
        u = c.execute("SELECT uin FROM users WHERE phone=?", (phone,)).fetchone()
        if u and c.execute("SELECT 1 FROM tokens WHERE uin=? LIMIT 1", (u["uin"],)).fetchone():
            in_app = True
            uin_for_code = u["uin"]
    if in_app:
        asyncio.create_task(service_send(
            uin_for_code,
            f"🔑 Код для входа в ICQ New: **{code}**\n\n"
            "Никому не сообщайте этот код, даже сотрудникам ICQ. "
            "Если вы не запрашивали код — просто проигнорируйте это сообщение.",
        ))
    return {"ok": True, "retry_in": RESEND_MS // 1000, "in_app": in_app}


@app.post("/api/auth/verify")
async def verify_code(body: VerifyIn, request: Request) -> dict:
    email = norm_email(body.email)
    phone = ("mail:" + email) if email else norm_phone(body.phone)
    now = now_ms()
    new_user_row = None
    owner_ids: List[int] = []
    with conn() as c:
        row = c.execute("SELECT * FROM otp WHERE phone=?", (phone,)).fetchone()
        if not row or now - row["created"] > CODE_TTL_MS:
            raise HTTPException(400, "Код не найден или истёк. Запросите новый")
        if row["attempts"] >= MAX_ATTEMPTS:
            raise HTTPException(429, "Слишком много попыток. Запросите новый код")
        if not secrets.compare_digest(row["code"], body.code.strip()):
            c.execute("UPDATE otp SET attempts=attempts+1 WHERE phone=?", (phone,))
            raise HTTPException(400, "Неверный код")
        if email:
            user = c.execute("SELECT * FROM users WHERE lower(email)=? OR phone=? ORDER BY (phone=?) DESC LIMIT 1",
                             (email, phone, phone)).fetchone()
        else:
            user = c.execute("SELECT * FROM users WHERE phone=?", (phone,)).fetchone()
        if user is not None and user["banned"]:
            raise HTTPException(403, "Аккаунт заблокирован администратором")
        created_now = user is None
        if user is None:
            name = (body.name or "").strip()[:40]
            if not name:
                return {"need_name": True}  # code stays valid, client asks for the name
            with _uin_lock:
                # random 9-digit ID (not 10000001, 10000002, ...)
                while True:
                    uin = secrets.randbelow(900_000_000) + 100_000_000
                    if not c.execute("SELECT 1 FROM users WHERE uin=?", (uin,)).fetchone():
                        break
                c.execute(
                    "INSERT INTO users(uin, name, phone, phone_key, last_seen, created, phone_privacy)"
                    " VALUES(?,?,?,?,?,?,'nobody')",
                    (uin, name, phone, "" if email else phone_key(phone), now, now),
                )
                if email:
                    c.execute("UPDATE users SET email=? WHERE uin=?", (email, uin))
            user = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
            # a new account is NOT announced and NOT added to anybody's contacts automatically
        c.execute("DELETE FROM otp WHERE phone=?", (phone,))
        had_sessions = c.execute("SELECT 1 FROM tokens WHERE uin=? LIMIT 1", (user["uin"],)).fetchone() is not None
        token = secrets.token_hex(32)
        device = (body.device or "Android")[:60]
        c.execute(
            "INSERT INTO tokens(token, uin, created, device, last_used) VALUES(?,?,?,?,?)",
            (token, user["uin"], now, device, now),
        )
    asyncio.create_task(announce_login(user["uin"], device, client_ip(request), had_sessions))
    with conn() as c:
        return {"token": token, "user": me_json(user, c), "new": created_now}


# ------------------------------------------------- e-mail codes and passwords
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[A-Za-z]{2,24}$")


def norm_email(raw: Optional[str]) -> str:
    e = (raw or "").strip().lower()
    if not e:
        return ""
    if not EMAIL_RE.match(e):
        raise HTTPException(400, "Введите правильный адрес почты")
    return e


def send_email(to: str, subject: str, text: str) -> bool:
    """SMTP from env: ICQ_SMTP_HOST, ICQ_SMTP_PORT (465 = SSL, else STARTTLS), ICQ_SMTP_USER,
    ICQ_SMTP_PASS, ICQ_SMTP_FROM. Without them the code goes to the server log / admin."""
    host = os.environ.get("ICQ_SMTP_HOST")
    if not host:
        return False
    import smtplib
    from email.message import EmailMessage
    port = int(os.environ.get("ICQ_SMTP_PORT", "465"))
    user = os.environ.get("ICQ_SMTP_USER", "")
    msg = EmailMessage()
    msg["From"] = os.environ.get("ICQ_SMTP_FROM", user)
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(text)
    try:
        if port == 465:
            srv = smtplib.SMTP_SSL(host, port, timeout=15)
        else:
            srv = smtplib.SMTP(host, port, timeout=15)
            srv.starttls()
        if user:
            srv.login(user, os.environ.get("ICQ_SMTP_PASS", ""))
        srv.send_message(msg)
        srv.quit()
        return True
    except Exception as e:  # noqa: BLE001
        print(f"[SMTP] {to}: {e}", flush=True)
        return False


async def request_email_code(email: str) -> dict:
    key = "mail:" + email
    now = now_ms()
    with conn() as c:
        u = c.execute("SELECT uin, banned FROM users WHERE lower(email)=? OR phone=? LIMIT 1", (email, key)).fetchone()
        if u is not None and u["banned"]:
            raise HTTPException(403, "Аккаунт заблокирован администратором")
        row = c.execute("SELECT created FROM otp WHERE phone=?", (key,)).fetchone()
        if row and now - row["created"] < RESEND_MS:
            wait = (RESEND_MS - (now - row["created"])) // 1000 + 1
            raise HTTPException(429, f"Подождите {wait} с и запросите код снова")
        code = f"{secrets.randbelow(1_000_000):06d}"
        c.execute("INSERT OR REPLACE INTO otp(phone, code, created, attempts, sent, claimed_by, claimed_at, sent_by, sent_at, sms_error)"
                  " VALUES(?,?,?,0,1,NULL,0,NULL,0,NULL)", (key, code, now))  # e-mail: nothing to send by SMS
        in_app = u is not None and c.execute("SELECT 1 FROM tokens WHERE uin=? LIMIT 1", (u["uin"],)).fetchone() is not None
    print(f"[OTP] {email}: {code}", flush=True)
    sent = await asyncio.to_thread(
        send_email, email, f"ICQ New: код {code}",
        f"Ваш код для входа в ICQ New: {code}\n\nНикому не сообщайте этот код. "
        "Если вы не запрашивали код — просто проигнорируйте это письмо.",
    )
    if in_app:
        asyncio.create_task(service_send(u["uin"], f"🔑 Код для входа в ICQ New: **{code}**\n\nНикому не сообщайте этот код."))
    return {"ok": True, "retry_in": RESEND_MS // 1000, "in_app": in_app, "email_sent": sent}


def hash_password(pw: str, salt: Optional[bytes] = None) -> str:
    import hashlib
    salt = salt or secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), salt, 200_000)
    return "pbkdf2$" + salt.hex() + "$" + h.hex()


def check_password(pw: str, stored: Optional[str]) -> bool:
    try:
        _, salt, h = (stored or "").split("$")
        return secrets.compare_digest(hash_password(pw, bytes.fromhex(salt)).split("$")[2], h)
    except ValueError:
        return False


class SetPasswordIn(BaseModel):
    password: str
    old: Optional[str] = None


@app.post("/api/me/password")
def set_password(body: SetPasswordIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    pw = body.password or ""
    if len(pw) < 6 or len(pw) > 128:
        raise HTTPException(400, "Пароль: от 6 символов")
    with conn() as c:
        row = c.execute("SELECT pw_hash FROM users WHERE uin=?", (uin,)).fetchone()
        if row["pw_hash"] and not check_password(body.old or "", row["pw_hash"]):
            raise HTTPException(403, "Неверный текущий пароль")
        c.execute("UPDATE users SET pw_hash=? WHERE uin=?", (hash_password(pw), uin))
    return {"ok": True}


class PasswordLoginIn(BaseModel):
    login: str
    password: str
    device: Optional[str] = None


_pw_fails: Dict[str, list] = {}


@app.post("/api/auth/password")
async def password_login(body: PasswordLoginIn, request: Request) -> dict:
    """Sign in with ID / @username / e-mail + password."""
    login = (body.login or "").strip().lstrip("@").lower()
    if not login:
        raise HTTPException(400, "Введите ID или имя пользователя")
    now = now_ms()
    fails = [t for t in _pw_fails.get(login, []) if now - t < 15 * 60_000]
    if len(fails) >= 8:
        raise HTTPException(429, "Слишком много попыток. Попробуйте через 15 минут")
    with conn() as c:
        user = None
        if login.isdigit():
            user = c.execute("SELECT * FROM users WHERE uin=?", (int(login),)).fetchone()
        elif "@" in login:
            user = c.execute("SELECT * FROM users WHERE lower(email)=? OR phone=? LIMIT 1",
                             (login, "mail:" + login)).fetchone()
        else:
            user = user_by_any_username(c, login)
        if user is None or user["uin"] in (BOT_UIN, SERVICE_UIN) or not check_password(body.password or "", _col(user, "pw_hash")):
            fails.append(now)
            _pw_fails[login] = fails
            raise HTTPException(400, "Неверный логин или пароль")
        if user["banned"]:
            raise HTTPException(403, "Аккаунт заблокирован администратором")
        _pw_fails.pop(login, None)
        had_sessions = c.execute("SELECT 1 FROM tokens WHERE uin=? LIMIT 1", (user["uin"],)).fetchone() is not None
        token = secrets.token_hex(32)
        device = (body.device or "Android")[:60]
        c.execute("INSERT INTO tokens(token, uin, created, device, last_used) VALUES(?,?,?,?,?)",
                  (token, user["uin"], now, device, now))
        res = {"token": token, "user": me_json(user, c), "new": False}
    asyncio.create_task(announce_login(user["uin"], device + " (по паролю)", client_ip(request), had_sessions))
    return res


async def announce_login(uin: int, device: str, ip: str, had_sessions: bool) -> None:
    """«ICQ» tells about every new sign-in (device, IP, time), like Telegram does."""
    when = time.strftime("%d.%m.%Y %H:%M", time.gmtime(time.time() + 3 * 3600)) + " (МСК)"
    text = (
        "🔐 **Новый вход в ваш аккаунт**\n\n"
        f"📱 Устройство: {device}\n"
        + (f"🌐 IP: {ip}\n" if ip else "")
        + f"🕒 Время: {when}\n\n"
        + ("Если это были не вы — откройте Настройки → Устройства и завершите этот сеанс."
           if had_sessions else "Добро пожаловать в ICQ New! Здесь будут коды входа и уведомления о новых устройствах.")
    )
    await service_send(uin, text)


# ---------------------------------------------------------------------- profile
@app.get("/api/me")
def me(authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        return me_json(c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone(), c)


async def broadcast_profile(uin: int) -> None:
    with conn() as c:
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
        targets = related_uins(c, uin)
    if (_col(row, "avatar_privacy", "all") or "all") == "all":
        await manager.send_many(targets, {"type": "profile", "user": user_json(row, viewer=0)})
    else:  # the photo depends on who looks
        for t in targets:
            await manager.send(t, {"type": "profile", "user": user_json(row, viewer=t)})
    await manager.send(uin, {"type": "me", "user": me_json(row)})  # own devices (e.g. a new green tick)


@app.post("/api/me")
async def update_me(body: ProfileIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Введите имя")
    with conn() as c:
        if body.about is None:
            c.execute("UPDATE users SET name=? WHERE uin=?", (name, uin))
        else:
            c.execute("UPDATE users SET name=?, about=? WHERE uin=?", (name, body.about.strip()[:140], uin))
        if body.birthday is not None:
            b = body.birthday.strip()
            if b and not re.fullmatch(r"\d{2}\.\d{2}(\.\d{4})?", b):
                raise HTTPException(400, "Дата рождения: ДД.ММ или ДД.ММ.ГГГГ")
            c.execute("UPDATE users SET birthday=? WHERE uin=?", (b or None, uin))
        if body.channel_id is not None:
            if body.channel_id:
                g = c.execute("SELECT kind, owner FROM groups WHERE id=?", (body.channel_id,)).fetchone()
                if not g or g["kind"] != "channel" or g["owner"] != uin:
                    raise HTTPException(400, "Можно указать только свой канал")
            c.execute("UPDATE users SET channel_id=? WHERE uin=?", (body.channel_id or None, uin))
        if body.color is not None:
            col = max(0, min(8, body.color))
            if col > 4 and not is_premium(c, uin):
                raise HTTPException(402, "Этот цвет доступен с ICQ Premium")
            c.execute("UPDATE users SET color=? WHERE uin=?", (col, uin))
        if body.surname is not None:
            c.execute("UPDATE users SET surname=? WHERE uin=?", (body.surname.strip() or None, uin))
        if body.email is not None:
            e = body.email.strip()
            if e and not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", e):
                raise HTTPException(400, "Неверный email")
            c.execute("UPDATE users SET email=? WHERE uin=?", (e or None, uin))
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
    await broadcast_profile(uin)
    return me_json(row)


def _check_jpeg(data: bytes) -> None:
    if len(data) > MAX_AVATAR:
        raise HTTPException(413, "Файл слишком большой")
    if not data.startswith(b"\xff\xd8"):
        raise HTTPException(400, "Нужен JPEG")


@app.post("/api/me/avatar")
async def upload_avatar(request: Request, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    data = await request.body()
    _check_jpeg(data)
    with open(os.path.join(AVATAR_DIR, f"{uin}.jpg"), "wb") as f:
        f.write(data)
    CLUSTER.push_file("avatars", f"{uin}.jpg", os.path.join(AVATAR_DIR, f"{uin}.jpg"))
    url = f"/avatars/{uin}.jpg?v={now_ms()}"
    with conn() as c:
        c.execute("UPDATE users SET avatar_url=? WHERE uin=?", (url, uin))
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
    await broadcast_profile(uin)
    return me_json(row)


@app.get("/api/users/{uin}")
def get_user(uin: int, authorization: Optional[str] = Header(None)) -> dict:
    me = require_uin(authorization)
    with conn() as c:
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
        k = c.execute("SELECT * FROM contacts WHERE owner=? AND contact=? AND created>0", (me, uin)).fetchone() if row else None
        back = c.execute("SELECT 1 FROM contacts WHERE owner=? AND contact=? AND created>0", (uin, me)).fetchone() if row else None
    if not row:
        raise HTTPException(404, "Пользователь не найден")
    d = user_json(row, with_phone=True)
    d["contact"] = k is not None
    d["added_me"] = back is not None
    if k is not None and k["alias"]:
        d["alias"] = k["alias"]
    return d


@app.get("/api/search")
def search(q: str = Query(""), authorization: Optional[str] = Header(None)) -> list:
    me_uin = require_uin(authorization)
    q = q.strip()
    if not q:
        return []
    with conn() as c:
        if q.startswith("@"):
            un = q[1:].replace("%", "").replace("_", "\\_")
            rows = c.execute(
                "SELECT * FROM users WHERE username LIKE ? ESCAPE '\\' AND uin<>? AND banned=0 LIMIT 30",
                (un + "%", me_uin),
            ).fetchall()
            return [user_json(r) for r in rows]
        if q.isdigit() or (q.startswith("+") and q[1:].isdigit()):
            d = digits(q)
            rows = c.execute(
                "SELECT * FROM users WHERE (uin=? OR phone=?) AND uin<>? AND searchable=1 AND banned=0 LIMIT 30",
                (int(d) if len(d) < 18 else -1, norm_phone(d), me_uin),
            ).fetchall()
        else:
            like = "%" + q.replace("%", "").replace("_", "") + "%"
            rows = c.execute(
                "SELECT * FROM users WHERE name LIKE ? AND uin<>? AND searchable=1 AND banned=0 ORDER BY name LIMIT 30",
                (like, me_uin),
            ).fetchall()
    return [user_json(r) for r in rows]


# -------------------------------------------------------------------- usernames
def check_username(c: sqlite3.Connection, raw: str, uin: Optional[int] = None, group_id: Optional[int] = None) -> Optional[str]:
    """Validates a @username (empty = remove). Unique across people and chats, case-insensitive."""
    un = (raw or "").strip().lstrip("@")
    if not un:
        return None
    if not USERNAME_RE.match(un):
        raise HTTPException(400, "Имя пользователя: 4–32 символа, латиница, цифры и _, начинается с буквы")
    u = c.execute("SELECT uin FROM users WHERE lower(username)=lower(?)", (un,)).fetchone()
    if u and u["uin"] != uin:
        raise HTTPException(409, "Это имя уже занято")
    g = c.execute("SELECT id FROM groups WHERE lower(username)=lower(?)", (un,)).fetchone()
    if g and g["id"] != group_id:
        raise HTTPException(409, "Это имя уже занято")
    x = c.execute("SELECT uin FROM user_usernames WHERE username=?", (un,)).fetchone()
    if x and (x["uin"] != uin or group_id is not None):
        raise HTTPException(409, "Это имя уже занято")
    lot = c.execute("SELECT sold_to FROM fragment_lots WHERE username=?", (un,)).fetchone()
    if lot and lot["sold_to"] is None:
        raise HTTPException(409, "Это имя продаётся на Fragment")
    return un


def user_by_any_username(c: sqlite3.Connection, un: str) -> Optional[sqlite3.Row]:
    """@name may be the main username or one of the extra ones."""
    u = c.execute("SELECT * FROM users WHERE lower(username)=lower(?) AND banned=0", (un,)).fetchone()
    if u:
        return u
    x = c.execute("SELECT uin FROM user_usernames WHERE username=?", (un,)).fetchone()
    return c.execute("SELECT * FROM users WHERE uin=? AND banned=0", (x["uin"],)).fetchone() if x else None


class ExtraUsernameIn(BaseModel):
    username: str


@app.get("/api/me/usernames")
def my_usernames(authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute("SELECT username FROM user_usernames WHERE uin=? ORDER BY ts", (uin,)).fetchall()
        return {"usernames": [r["username"] for r in rows], "limit": EXTRA_USERNAMES_MAX, "price": FRAGMENT_PRICE}


FRAGMENT_PRICE = int(os.environ.get("ICQ_FRAGMENT_PRICE", "50"))  # any free @name (4+ letters), Gold
EXTRA_USERNAMES_MAX = 10
LOT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_]{0,31}$")


def _extra_slot_check(c: sqlite3.Connection, uin: int) -> None:
    n = c.execute("SELECT COUNT(*) AS n FROM user_usernames WHERE uin=?", (uin,)).fetchone()["n"]
    if n >= EXTRA_USERNAMES_MAX:
        raise HTTPException(400, f"Максимум {EXTRA_USERNAMES_MAX} дополнительных имён")


@app.post("/api/me/usernames")
async def add_username(body: ExtraUsernameIn, authorization: Optional[str] = Header(None)) -> dict:
    """Fragment: buy any free @name (4+ letters) as an extra name for FRAGMENT_PRICE Gold."""
    uin = require_uin(authorization)
    with conn() as c:
        un = check_username(c, body.username, uin=uin)
        if not un:
            raise HTTPException(400, "Введите имя")
        me = c.execute("SELECT username FROM users WHERE uin=?", (uin,)).fetchone()
        if (me["username"] or "").lower() == un.lower():
            raise HTTPException(400, "Это ваше основное имя")
        if c.execute("SELECT 1 FROM user_usernames WHERE username=?", (un,)).fetchone():
            raise HTTPException(400, "Это имя уже ваше")
        _extra_slot_check(c, uin)
        _charge(c, uin, FRAGMENT_PRICE, f"Fragment: @{un}")
        c.execute("INSERT INTO user_usernames(username, uin, ts) VALUES(?,?,?)", (un, uin, now_ms()))
    await broadcast_profile(uin)
    return my_usernames(authorization)


@app.get("/api/fragment")
def fragment(authorization: Optional[str] = Header(None)) -> dict:
    """Fragment: the @names on sale (set by the admin) + the price of any other free name."""
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute("SELECT username, price FROM fragment_lots WHERE sold_to IS NULL ORDER BY length(username), price DESC").fetchall()
        bal = c.execute("SELECT balance FROM users WHERE uin=?", (uin,)).fetchone()["balance"] or 0
        return {"lots": [{"username": r["username"], "price": r["price"]} for r in rows],
                "custom_price": FRAGMENT_PRICE, "balance": bal}


class BuyLotIn(BaseModel):
    username: str


@app.post("/api/fragment/buy")
async def fragment_buy(body: BuyLotIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    name = body.username.strip().lstrip("@")
    with conn() as c:
        lot = c.execute("SELECT * FROM fragment_lots WHERE username=?", (name,)).fetchone()
        if not lot or lot["sold_to"] is not None:
            raise HTTPException(404, "Это имя уже продано")
        _extra_slot_check(c, uin)
        _charge(c, uin, lot["price"], f"Fragment: @{lot['username']}")
        c.execute("UPDATE fragment_lots SET sold_to=?, sold_ts=? WHERE username=?", (uin, now_ms(), lot["username"]))
        c.execute("INSERT OR REPLACE INTO user_usernames(username, uin, ts) VALUES(?,?,?)", (lot["username"], uin, now_ms()))
    await broadcast_profile(uin)
    asyncio.create_task(service_send(uin, f"💎 Имя **@{lot['username']}** теперь ваше (Fragment)."))
    return my_usernames(authorization)




@app.delete("/api/me/usernames/{name}")
async def remove_username(name: str, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        c.execute("DELETE FROM user_usernames WHERE username=? AND uin=?", (name.lstrip("@"), uin))
    await broadcast_profile(uin)
    return my_usernames(authorization)


@app.post("/api/me/username")
async def set_username(body: UsernameIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        un = check_username(c, body.username, uin=uin)
        c.execute("UPDATE users SET username=? WHERE uin=?", (un, uin))
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
    await broadcast_profile(uin)
    return me_json(row)


@app.get("/api/username/check")
def username_check(u: str = Query(""), authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        try:
            check_username(c, u, uin=uin)
            return {"ok": True, "text": "Имя свободно"}
        except HTTPException as e:
            return {"ok": False, "text": str(e.detail)}


@app.get("/api/resolve/{username}")
def resolve_username(username: str, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    un = username.strip().lstrip("@")
    with conn() as c:
        u = user_by_any_username(c, un)
        if u:
            return {"type": "user", "user": user_json(u, c=c)}
        g = c.execute("SELECT * FROM groups WHERE lower(username)=lower(?)", (un,)).fetchone()
        if g:
            d = group_json(c, g, with_members=False, public_view=channel_public(g))
            d["member"] = is_member(c, g["id"], uin)
            return {"type": "group", "group": d}
    raise HTTPException(404, "Не найдено")


# --------------------------------------------------------------------- sessions
def session_id(token: str) -> str:
    import hashlib
    return hashlib.sha256(token.encode()).hexdigest()[:12]


@app.get("/api/sessions")
def sessions(authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    mine = authorization[7:].strip()
    with conn() as c:
        rows = c.execute("SELECT * FROM tokens WHERE uin=? ORDER BY last_used DESC", (uin,)).fetchall()
    return [
        {
            "id": session_id(r["token"]),
            "device": r["device"] or "Android",
            "created": r["created"],
            "last_used": r["last_used"] or r["created"],
            "current": r["token"] == mine,
            "online": manager.token_online(uin, r["token"]),
        }
        for r in rows
    ]


@app.delete("/api/sessions/{sid}")
async def end_session(sid: str, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    mine = authorization[7:].strip()
    with conn() as c:
        rows = c.execute("SELECT token FROM tokens WHERE uin=?", (uin,)).fetchall()
        victims = [r["token"] for r in rows if (sid == "others" and r["token"] != mine) or session_id(r["token"]) == sid]
        for t in victims:
            c.execute("DELETE FROM tokens WHERE token=?", (t,))
    await manager.kick_tokens(uin, victims)
    return {"ok": True, "ended": len(victims)}


# -------------------------------------------------------------------- contacts
def contact_json(c: sqlite3.Connection, owner: int, row: sqlite3.Row) -> dict:
    """A person in [owner]'s contact list: my name for him, his number (if he lets me see it), arrows."""
    d = user_json(row, viewer=owner, with_phone=True, c=c)
    k = c.execute("SELECT * FROM contacts WHERE owner=? AND contact=? AND created>0", (owner, row["uin"])).fetchone()
    back = c.execute("SELECT created FROM contacts WHERE owner=? AND contact=? AND created>0", (row["uin"], owner)).fetchone()
    d["contact"] = k is not None
    d["added_me"] = back is not None
    d["mutual"] = k is not None and back is not None
    d["added_at"] = (k["created"] if k is not None else (back["created"] if back else 0)) or 0
    if k is not None and k["alias"]:
        d["alias"] = k["alias"]
        d["real_name"] = row["name"]
        d["name"] = k["alias"]
    return d


@app.get("/api/contacts")
def contacts(authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute(
            "SELECT u.* FROM contacts k JOIN users u ON u.uin=k.contact WHERE k.owner=? AND k.created>0"
            " ORDER BY lower(COALESCE(k.alias, u.name))",
            (uin,),
        ).fetchall()
        return [contact_json(c, uin, r) for r in rows]


@app.get("/api/contacts/incoming")
def incoming_contacts(authorization: Optional[str] = Header(None)) -> list:
    """People who added me to their contacts but I have not added them back."""
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute(
            "SELECT u.* FROM contacts k JOIN users u ON u.uin=k.owner WHERE k.contact=? AND k.created>0 AND u.banned=0"
            " AND u.uin<>? AND NOT EXISTS (SELECT 1 FROM contacts x WHERE x.owner=? AND x.contact=k.owner AND x.created>0)"
            " AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.owner=? AND b.blocked=k.owner)"
            " ORDER BY k.created DESC LIMIT 200",
            (uin, BOT_UIN, uin, uin),
        ).fetchall()
        return [contact_json(c, uin, r) for r in rows]


@app.post("/api/contacts")
async def add_contact(body: ContactIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        row = None
        if body.uin:
            row = c.execute("SELECT * FROM users WHERE uin=? AND banned=0", (body.uin,)).fetchone()
        elif body.phone:
            key = digits(body.phone)[-8:]
            if len(key) < 8:
                raise HTTPException(400, "Введите номер полностью, с кодом страны")
            row = c.execute("SELECT * FROM users WHERE phone_key=? AND banned=0", (key,)).fetchone()
            if not row:
                raise HTTPException(404, "Этот номер ещё не зарегистрирован в ICQ")
        elif body.query:
            q = body.query.strip().lstrip("@")
            if q.isdigit():
                row = c.execute("SELECT * FROM users WHERE uin=? AND banned=0", (int(q),)).fetchone()
            else:
                row = c.execute("SELECT * FROM users WHERE username=? COLLATE NOCASE AND banned=0", (q,)).fetchone()
        if not row:
            raise HTTPException(404, "Пользователь не найден")
        if row["uin"] == uin:
            raise HTTPException(400, "Нельзя добавить самого себя")
        alias = (body.name or "").strip()[:64] or None
        prev = c.execute("SELECT created FROM contacts WHERE owner=? AND contact=?", (uin, row["uin"])).fetchone()
        existed = prev is not None and (prev["created"] or 0) > 0
        if existed:
            c.execute(
                "UPDATE contacts SET alias=?, share_phone=? WHERE owner=? AND contact=?",
                (alias, int(body.share_phone), uin, row["uin"]),
            )
        elif prev is not None:  # we only chatted before: now it becomes a real contact
            c.execute(
                "UPDATE contacts SET alias=?, share_phone=?, created=? WHERE owner=? AND contact=?",
                (alias, int(body.share_phone), now_ms(), uin, row["uin"]),
            )
        else:
            c.execute(
                "INSERT INTO contacts(owner, contact, alias, share_phone, created) VALUES(?,?,?,?,?)",
                (uin, row["uin"], alias, int(body.share_phone), now_ms()),
            )
        result = contact_json(c, uin, row)
        me_row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
        note = contact_json(c, row["uin"], me_row)
    if not existed and row["uin"] != BOT_UIN:
        await manager.send(row["uin"], {"type": "contact_added", "user": note})
    elif existed:
        await manager.send(row["uin"], {"type": "profile", "user": user_json(me_row, viewer=row["uin"])})
    return result


@app.delete("/api/contacts/{other}")
def remove_contact(other: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        # keep the chat, drop only the "contact" mark
        c.execute("UPDATE contacts SET created=0, alias=NULL, share_phone=0 WHERE owner=? AND contact=?", (uin, other))
    return {"ok": True}


@app.post("/api/contacts/sync")
def sync_contacts(body: SyncIn, authorization: Optional[str] = Header(None)) -> list:
    """Phone-book sync: the app sends the last 8 digits of every number in the phone book;
    registered users that match become contacts. Keys are remembered, so people who join
    later are added automatically."""
    uin = require_uin(authorization)
    if len(body.keys) > 10000:
        raise HTTPException(400, "Слишком много контактов")
    keys = sorted({digits(k)[-8:] for k in body.keys if len(digits(k)) >= 8})
    found: list = []
    with conn() as c:
        for i in range(0, len(keys), 500):
            chunk = keys[i : i + 500]
            marks = ",".join("?" * len(chunk))
            found += c.execute(
                f"SELECT * FROM users WHERE phone_key IN ({marks}) AND uin<>? AND searchable=1 AND banned=0",
                (*chunk, uin),
            ).fetchall()
        c.executemany("INSERT OR IGNORE INTO phonebook(owner, key) VALUES(?,?)", [(uin, k) for k in keys])
        # only shown as "these people from your phone book are in ICQ" — not added automatically
        return [contact_json(c, uin, r) for r in found]


# ---------------------------------------------------------------------- blocks
@app.get("/api/blocks")
def list_blocks(authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        return [r["blocked"] for r in c.execute("SELECT blocked FROM blocks WHERE owner=?", (uin,)).fetchall()]


@app.post("/api/blocks")
def set_block(body: BlockIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    if body.uin == uin:
        raise HTTPException(400, "Нельзя заблокировать себя")
    with conn() as c:
        if body.block:
            c.execute("INSERT OR IGNORE INTO blocks(owner, blocked) VALUES(?,?)", (uin, body.uin))
        else:
            c.execute("DELETE FROM blocks WHERE owner=? AND blocked=?", (uin, body.uin))
    return {"ok": True, "uin": body.uin, "blocked": body.block}


# -------------------------------------------------------------------- messages
def visible_clause() -> str:
    return (
        "(m.from_uin=:u OR m.to_uin=:u OR m.group_id IN (SELECT gid FROM group_members WHERE uin=:u))"
    )


@app.get("/api/updates")
def updates(rev: int = 0, limit: int = 500, authorization: Optional[str] = Header(None)) -> dict:
    """Everything that changed after `rev`: new messages, edits, deletions and reactions."""
    uin = require_uin(authorization)
    limit = max(1, min(limit, 1000))
    with conn() as c:
        rows = c.execute(
            f"SELECT m.* FROM messages m WHERE m.rev>:r AND {visible_clause()} ORDER BY m.rev LIMIT :l",
            {"r": rev, "u": uin, "l": limit},
        ).fetchall()
        items = [msg_json(c, r, uin) for r in rows]
        top = c.execute("SELECT v FROM meta WHERE k='rev'").fetchone()["v"]
        last = items[-1]["rev"] if items else top
        until = last if len(items) == limit else max(last, top)
        clears = [
            {"peer": r["peer"], "upto": r["upto"]}
            for r in c.execute(
                "SELECT peer, upto FROM clears WHERE uin=? AND rev>? AND rev<=? ORDER BY rev", (uin, rev, until)
            ).fetchall()
        ]
    return {"messages": items, "rev": until, "more": len(items) == limit, "clears": clears}


@app.get("/api/messages")
def messages(after: int = 0, authorization: Optional[str] = Header(None)) -> list:
    """v2 compatibility."""
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute(
            f"SELECT m.* FROM messages m WHERE m.id>:a AND {visible_clause()} ORDER BY m.id LIMIT 5000",
            {"a": after, "u": uin},
        ).fetchall()
        return [msg_json(c, r, uin) for r in rows]


@app.get("/api/read_marks")
def read_marks(authorization: Optional[str] = Header(None)) -> list:
    """For every peer: highest id of MY messages that the peer has already read.
    Groups are reported as peer = -group_id."""
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute(
            "SELECT to_uin AS peer, MAX(id) AS upto FROM messages"
            " WHERE from_uin=? AND is_read=1 AND group_id IS NULL GROUP BY to_uin",
            (uin,),
        ).fetchall()
        out = [{"peer": r["peer"], "upto": r["upto"]} for r in rows]
        grows = c.execute(
            "SELECT m.gid AS gid, MAX(o.read_upto) AS upto FROM group_members m"
            " JOIN group_members o ON o.gid=m.gid AND o.uin<>m.uin WHERE m.uin=? GROUP BY m.gid",
            (uin,),
        ).fetchall()
        out += [{"peer": -r["gid"], "upto": r["upto"] or 0} for r in grows if r["upto"]]
    return out


# ------------------------------------------------------------- privacy / files
class PrivacyIn(BaseModel):
    searchable: bool
    show_online: bool
    avatar_privacy: Optional[str] = None
    phone_privacy: Optional[str] = None


@app.post("/api/me/privacy")
async def set_privacy(body: PrivacyIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        c.execute(
            "UPDATE users SET searchable=?, show_online=? WHERE uin=?",
            (int(body.searchable), int(body.show_online), uin),
        )
        if body.avatar_privacy in PRIVACY_MODES:
            c.execute("UPDATE users SET avatar_privacy=? WHERE uin=?", (body.avatar_privacy, uin))
        if body.phone_privacy in PRIVACY_MODES:
            c.execute("UPDATE users SET phone_privacy=? WHERE uin=?", (body.phone_privacy, uin))
        row = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
    await broadcast_profile(uin)
    return me_json(row)


@app.post("/api/logout")
def logout(authorization: Optional[str] = Header(None)) -> dict:
    require_uin(authorization)
    with conn() as c:
        c.execute("DELETE FROM tokens WHERE token=?", (authorization[7:].strip(),))
    return {"ok": True}


async def wipe_user(uin: int) -> None:
    """Removes a user with all his data (used by 'delete account' and by the admin)."""
    with conn() as c:
        targets = related_uins(c, uin)
        paths = [r["path"] for r in c.execute("SELECT path FROM files WHERE owner=?", (uin,)).fetchall()]
        gids = [r["gid"] for r in c.execute("SELECT gid FROM group_members WHERE uin=?", (uin,)).fetchall()]
        c.execute("DELETE FROM files WHERE owner=?", (uin,))
        c.execute("DELETE FROM reactions WHERE uin=?", (uin,))
        c.execute("DELETE FROM messages WHERE (from_uin=? OR to_uin=?) AND group_id IS NULL", (uin, uin))
        c.execute("DELETE FROM contacts WHERE owner=? OR contact=?", (uin, uin))
        c.execute("DELETE FROM phonebook WHERE owner=?", (uin,))
        c.execute("DELETE FROM blocks WHERE owner=? OR blocked=?", (uin, uin))
        c.execute("DELETE FROM tokens WHERE uin=?", (uin,))
        c.execute("DELETE FROM push_tokens WHERE uin=?", (uin,))
        c.execute("DELETE FROM group_bans WHERE uin=?", (uin,))
        c.execute("DELETE FROM users WHERE uin=?", (uin,))
    for p in paths:
        try:
            os.remove(p)
        except OSError:
            pass
    try:
        os.remove(os.path.join(AVATAR_DIR, f"{uin}.jpg"))
    except OSError:
        pass
    CLUSTER.delete_file("avatars", f"{uin}.jpg")
    for p in paths:
        CLUSTER.delete_file("uploads", os.path.basename(p))
    await manager.kick(uin)
    for gid in gids:
        await leave_group(gid, uin, actor=uin, system_text="покинул(а) группу", deleted_user=True)
    await manager.send_many(targets, {"type": "gone", "uin": uin})


@app.delete("/api/me")
async def delete_account(authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    await wipe_user(uin)
    return {"ok": True}


def safe_name(name: str) -> str:
    name = re.sub(r"[^\w.\- ()]", "_", name.strip(), flags=re.UNICODE)[:100]
    return name or "file"


@app.post("/api/upload")
async def upload(
    request: Request,
    name: str = Query("file"),
    mime: str = Query("application/octet-stream"),
    dur: int = Query(0),
    authorization: Optional[str] = Header(None),
) -> dict:
    uin = require_uin(authorization)
    declared = int(request.headers.get("content-length") or 0)
    with conn() as c0:
        limit = MAX_UPLOAD if is_premium(c0, uin) else FREE_UPLOAD
    if declared > limit:
        raise HTTPException(413, "Файл больше 4 ГБ" if limit == MAX_UPLOAD else "Без Premium — файлы до 2 ГБ")
    fid = secrets.token_hex(16)
    path = os.path.join(UPLOAD_DIR, fid)
    size = 0
    try:
        # streamed straight to disk: big files never sit in memory
        with open(path, "wb") as f:
            async for chunk in request.stream():
                if not chunk:
                    continue
                size += len(chunk)
                if size > limit:
                    raise HTTPException(413, "Файл больше 4 ГБ" if limit == MAX_UPLOAD else "Без Premium — файлы до 2 ГБ")
                f.write(chunk)
    except BaseException:
        try:
            os.remove(path)
        except OSError:
            pass
        raise
    if size == 0:
        os.remove(path)
        raise HTTPException(400, "Пустой файл")
    with conn() as c:
        c.execute(
            "INSERT INTO files(id, owner, name, mime, size, dur, path, created) VALUES(?,?,?,?,?,?,?,?)",
            (fid, uin, safe_name(name), mime[:100], size, max(dur, 0), path, now_ms()),
        )
    CLUSTER.push_file("uploads", fid, path)  # a copy on every server of the cluster
    return {"id": fid, "name": safe_name(name), "size": size, "mime": mime[:100], "dur": max(dur, 0)}


def can_access_file(c: sqlite3.Connection, uin: int, fid: str) -> Optional[sqlite3.Row]:
    f = c.execute("SELECT * FROM files WHERE id=?", (fid,)).fetchone()
    if not f:
        return None
    if f["owner"] == uin:
        return f
    ok = c.execute(
        f"SELECT 1 FROM messages m WHERE m.att_id=:f AND m.deleted=0 AND {visible_clause()} LIMIT 1",
        {"f": fid, "u": uin},
    ).fetchone()
    if not ok:  # a publication: they are shown in profiles
        s = c.execute("SELECT uin FROM stories WHERE att_id=? LIMIT 1", (fid,)).fetchone()
        if s:
            ok = True
    return f if ok else None


@app.get("/api/files/{fid}")
def download(fid: str, t: Optional[str] = None, authorization: Optional[str] = Header(None)):
    """Private: only the uploader and the participants of a message that carries the file."""
    uin = require_uin(authorization if authorization or not t else "Bearer " + t)
    with conn() as c:
        f = can_access_file(c, uin, fid)
    if not f:
        raise HTTPException(404, "Файл не найден")
    p = local_blob("uploads", os.path.basename(f["path"])) or f["path"]
    if not os.path.exists(p):
        raise HTTPException(404, "Файл не найден")
    return FileResponse(p, media_type=f["mime"], filename=f["name"])


# ----------------------------------------------------------------------- groups
ROLES = ("owner", "admin", "member")


def insert_system(c: sqlite3.Connection, gid: int, actor: int, text: str) -> sqlite3.Row:
    cur = c.execute(
        "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, group_id, rev)"
        " VALUES(?,?,0,?,?,'system',?,?)",
        ("sys-" + secrets.token_hex(8), actor, text[:300], now_ms(), gid, next_rev(c)),
    )
    return c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()


def uname(c: sqlite3.Connection, uin: int) -> str:
    r = c.execute("SELECT name FROM users WHERE uin=?", (uin,)).fetchone()
    return r["name"] if r else f"ID {uin}"


def role_of(c: sqlite3.Connection, gid: int, uin: int) -> Optional[str]:
    r = c.execute("SELECT role FROM group_members WHERE gid=? AND uin=?", (gid, uin)).fetchone()
    return r["role"] if r else None


def is_admin(c: sqlite3.Connection, gid: int, uin: int) -> bool:
    return role_of(c, gid, uin) in ("owner", "admin")


def is_group_banned(c: sqlite3.Connection, gid: int, uin: int) -> bool:
    return c.execute("SELECT 1 FROM group_bans WHERE gid=? AND uin=?", (gid, uin)).fetchone() is not None


def muted_left(c: sqlite3.Connection, gid: int, uin: int) -> int:
    """0 = can write, -1 = muted forever, >0 = ms left."""
    r = c.execute("SELECT muted_until FROM group_members WHERE gid=? AND uin=?", (gid, uin)).fetchone()
    if not r or not r["muted_until"]:
        return 0
    if r["muted_until"] < 0:
        return -1
    left = r["muted_until"] - now_ms()
    return left if left > 0 else 0


def group_settings(g: sqlite3.Row) -> dict:
    try:
        stored = json.loads(g["settings"] or "{}")
    except ValueError:
        stored = {}
    return {k: stored.get(k, v) for k, v in GROUP_DEFAULTS.items()}


def insert_bot(c: sqlite3.Connection, gid: int, text: str) -> sqlite3.Row:
    """A message from the chat's bot (from_uin = 0, kind 'bot')."""
    cur = c.execute(
        "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, group_id, rev)"
        " VALUES(?,0,0,?,?,'bot',?,?)",
        ("bot-" + secrets.token_hex(8), text[:1000], now_ms(), gid, next_rev(c)),
    )
    return c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()


def group_json(c: sqlite3.Connection, g: sqlite3.Row, with_members: bool = True, admins_only: bool = False,
               public_view: bool = False) -> dict:
    """public_view: a channel seen by a subscriber — the creator is not shown (Telegram-like)."""
    d = {
        "id": g["id"],
        "name": g["name"],
        "avatar_url": g["avatar_url"],
        "owner": g["owner"],
        "created": g["created"],
        "kind": g["kind"] or "group",
        "about": g["about"] or "",
        "public": bool(g["public"]),
        "username": g["username"],
        "verified": bool(g["verified"]),
        "premium": (_col(g, "premium_until", 0) or 0) > now_ms(),
        "settings": group_settings(g),
        "bot_name": g["bot_name"] or "ICQ Бот",
        "bot_avatar": g["bot_avatar"],
        "count": c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (g["id"],)).fetchone()["n"]
        + (1 if (g["kind"] or "group") == "group" else 0),
    }
    if with_members:
        extra = " AND m.role IN ('owner','admin')" if admins_only else ""
        rows = c.execute(
            "SELECT u.*, m.role AS role, m.muted_until AS muted_until FROM group_members m"
            f" JOIN users u ON u.uin=m.uin WHERE m.gid=?{extra}"
            " ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, m.joined LIMIT 1000",
            (g["id"],),
        ).fetchall()
        out = []
        for r in rows:
            if public_view and r["role"] == "owner":
                continue
            u = user_json(r)
            u["role"] = r["role"]
            mu = r["muted_until"] or 0
            u["muted_until"] = mu if (mu < 0 or mu > now_ms()) else 0
            out.append(u)
        if (g["kind"] or "group") == "group":
            # the chat's bot is shown as a regular participant (with the name / photo set by the creator)
            out.insert(1 if out else 0, {
                "uin": BOT_UIN, "name": g["bot_name"] or "ICQ Бот", "avatar_url": g["bot_avatar"],
                "about": "Бот этого чата", "online": False, "last_seen": 0, "username": "icqbot",
                "verified": True, "bot": True, "role": "bot", "muted_until": 0,
            })
        d["members"] = out
    if public_view:
        d["owner"] = 0
    return d


def channel_public(g: sqlite3.Row) -> bool:
    return (g["kind"] or "group") == "channel"


def group_view(c: sqlite3.Connection, g: sqlite3.Row, viewer: int) -> dict:
    """Channel subscribers: the creator is hidden; the subscriber list only if the creator allowed it."""
    if channel_public(g) and not is_admin(c, g["id"], viewer):
        return group_json(c, g, admins_only=not group_settings(g)["show_subscribers"], public_view=True)
    return group_json(c, g)


async def push_group(gid: int, extra_targets: Optional[List[int]] = None, sys_rows=()) -> None:
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if not g:
            return
        members = group_members(c, gid)
        full = {"type": "group", "group": group_json(c, g)}
        if g["kind"] == "channel":
            short = {"type": "group", "group": group_json(
                c, g, admins_only=not group_settings(g)["show_subscribers"], public_view=True)}
            admins = {m for m in members if is_admin(c, gid, m)}
        else:
            short, admins = full, set(members)
        msgs = [msg_json(c, r) for r in sys_rows]
    for m in set(members + (extra_targets or [])):
        await manager.send(m, full if m in admins else short)
    for m in msgs:
        await manager.send_many(members, {"type": "message", "message": m})


def require_group(c: sqlite3.Connection, gid: int, uin: int) -> sqlite3.Row:
    g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
    if not g or not is_member(c, gid, uin):
        raise HTTPException(404, "Группа не найдена")
    return g


def require_group_admin(c: sqlite3.Connection, gid: int, uin: int) -> sqlite3.Row:
    g = require_group(c, gid, uin)
    if not is_admin(c, gid, uin):
        raise HTTPException(403, "Это могут только администраторы")
    return g


def word(g: sqlite3.Row) -> str:
    return "канал" if g["kind"] == "channel" else "группу"


@app.get("/api/groups")
def my_groups(authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute(
            "SELECT g.* FROM groups g JOIN group_members m ON m.gid=g.id WHERE m.uin=? ORDER BY g.id", (uin,)
        ).fetchall()
        return [group_view(c, g, uin) for g in rows]


@app.get("/api/groups/{gid}")
def get_group(gid: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if g and not is_member(c, gid, uin) and g["public"]:
            d = group_json(c, g, with_members=False, public_view=channel_public(g))  # preview of a public chat
            d["member"] = False
            return d
        d = group_view(c, require_group(c, gid, uin), uin)
        d["member"] = True
        return d


@app.get("/api/search/chats")
def search_chats(q: str = Query(""), authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    q = q.strip().lstrip("@")
    with conn() as c:
        if q:
            like = "%" + q.replace("%", "").replace("_", "") + "%"
            rows = c.execute(
                "SELECT g.*, (SELECT COUNT(*) FROM group_members m WHERE m.gid=g.id) AS n FROM groups g"
                " WHERE (g.public=1 OR g.username IS NOT NULL) AND (g.name LIKE ? OR g.about LIKE ? OR g.username LIKE ?)"
                " ORDER BY g.verified DESC, (g.premium_until>?) DESC, n DESC LIMIT 50",
                (like, like, like, now_ms()),
            ).fetchall()
        else:  # the showcase: only verified (green tick) and Premium channels / chats
            rows = c.execute(
                "SELECT g.*, (SELECT COUNT(*) FROM group_members m WHERE m.gid=g.id) AS n FROM groups g"
                " WHERE g.verified=1 OR g.premium_until>?"
                " ORDER BY g.verified DESC, CASE g.kind WHEN 'channel' THEN 0 ELSE 1 END, n DESC LIMIT 60",
                (now_ms(),),
            ).fetchall()
        out = []
        for g in rows:
            d = group_json(c, g, with_members=False, public_view=channel_public(g))
            d["member"] = is_member(c, g["id"], uin)
            out.append(d)
        return out


def is_premium(c: sqlite3.Connection, uin: int) -> bool:
    r = c.execute("SELECT premium_until FROM users WHERE uin=?", (uin,)).fetchone()
    return bool(r and (r["premium_until"] or 0) > now_ms())


def _charge(c: sqlite3.Connection, uin: int, price: int, reason: str) -> int:
    """Takes money from the balance (raises 402 when it is not enough). Returns the new balance."""
    bal = c.execute("SELECT balance FROM users WHERE uin=?", (uin,)).fetchone()["balance"] or 0
    if bal < price:
        raise HTTPException(402, f"Недостаточно средств: нужно {price} Gold, на балансе {bal} Gold")
    c.execute("UPDATE users SET balance=balance-? WHERE uin=?", (price, uin))
    c.execute("INSERT INTO balance_log(uin, delta, reason, ts) VALUES(?,?,?,?)", (uin, -price, reason[:200], now_ms()))
    return bal - price


@app.get("/api/premium")
def premium_info(authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        u = c.execute("SELECT balance, premium_until FROM users WHERE uin=?", (uin,)).fetchone()
        log = c.execute("SELECT delta, reason, ts FROM balance_log WHERE uin=? ORDER BY id DESC LIMIT 30", (uin,)).fetchall()
        groups = c.execute(
            "SELECT g.id, g.name, g.kind, g.premium_until, g.owner FROM groups g JOIN group_members m ON m.gid=g.id"
            " WHERE m.uin=? AND (g.owner=? OR m.role IN ('owner','admin')) ORDER BY g.name", (uin, uin),
        ).fetchall()
    return {
        "balance": u["balance"] or 0, "premium_until": u["premium_until"] or 0, "currency": "Gold",
        "plans": PREMIUM_PLANS, "group_plans": GROUP_PLANS, "gifts": GIFTS, "perks": PREMIUM_PERKS,
        "history": [{"delta": r["delta"], "reason": r["reason"], "ts": r["ts"]} for r in log],
        "my_groups": [{"id": g["id"], "name": g["name"], "kind": g["kind"], "premium_until": g["premium_until"] or 0,
                       "owner": g["owner"] == uin}
                      for g in groups],
        "top_up": "Чтобы пополнить баланс, напишите в поддержку: оплата принимается вручную, "
                  "администратор зачислит сумму, и придёт уведомление от «ICQ».",
        "support_uin": SUPPORT_UIN,
    }


class BuyPremiumIn(BaseModel):
    months: int
    group: Optional[int] = None  # Premium for my group / channel
    to: Optional[int] = None  # Premium as a present to another user


@app.post("/api/premium/buy")
async def premium_buy(body: BuyPremiumIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    plans = GROUP_PLANS if body.group else PREMIUM_PLANS
    plan = next((p for p in plans if p["months"] == body.months), None)
    if not plan:
        raise HTTPException(400, "Нет такого тарифа")
    now = now_ms()
    note = None
    with conn() as c:
        if body.group:
            g = c.execute("SELECT * FROM groups WHERE id=?", (body.group,)).fetchone()
            if not g:
                raise HTTPException(404, "Чат не найден")
            require_group_admin(c, body.group, uin)
            bal = _charge(c, uin, plan["price"], f"Premium для «{g['name']}» на {body.months} мес.")
            until = max(now, g["premium_until"] or 0) + body.months * MONTH_MS
            c.execute("UPDATE groups SET premium_until=? WHERE id=?", (until, body.group))
        else:
            target = body.to or uin
            t = c.execute("SELECT * FROM users WHERE uin=? AND banned=0", (target,)).fetchone()
            if not t or target in (BOT_UIN, SERVICE_UIN):
                raise HTTPException(404, "Пользователь не найден")
            who = "себе" if target == uin else f"в подарок: {t['name']}"
            bal = _charge(c, uin, plan["price"], f"Premium на {body.months} мес. ({who})")
            until = max(now, t["premium_until"] or 0) + body.months * MONTH_MS
            c.execute("UPDATE users SET premium_until=? WHERE uin=?", (until, target))
            if target != uin:
                me = c.execute("SELECT name FROM users WHERE uin=?", (uin,)).fetchone()["name"]
                note = (target, f"🎁 {me} подарил(а) вам **ICQ Premium** на {body.months} мес. Теперь у вас синяя галочка ✔")
    if note:
        asyncio.create_task(service_send(*note))
    return {"balance": bal, "until": until}


class SendGiftIn(BaseModel):
    to: int
    gift: str
    text: str = ""
    anonymous: bool = False


@app.post("/api/gifts/send")
async def send_gift(body: SendGiftIn, authorization: Optional[str] = Header(None)) -> dict:
    """A gift (also to myself): shown in the profile; the recipient gets a gift message in the chat."""
    uin = require_uin(authorization)
    g = GIFT_BY_ID.get(body.gift)
    if not g:
        raise HTTPException(400, "Нет такого подарка")
    text = body.text.strip()[:200]
    with conn() as c:
        t = c.execute("SELECT * FROM users WHERE uin=? AND banned=0", (body.to,)).fetchone()
        if not t or body.to in (BOT_UIN, SERVICE_UIN):
            raise HTTPException(404, "Пользователь не найден")
        if body.to != uin and is_blocked(c, body.to, uin):
            raise HTTPException(403, "Пользователь ограничил сообщения")
        bal = _charge(c, uin, g["price"], f"Подарок {g['emoji']} {g['name']}" + ("" if body.to == uin else f" → {t['name']}"))
        now = now_ms()
        c.execute("INSERT INTO user_gifts(to_uin, from_uin, gift, text, anonymous, ts) VALUES(?,?,?,?,?,?)",
                  (body.to, uin, g["id"], text, int(body.anonymous), now))
        msg = None
        if body.to != uin:
            for a, b in ((uin, body.to), (body.to, uin)):
                c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (a, b))
            cur = c.execute(
                "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, rev, att_name) VALUES(?,?,?,?,?,'gift',?,?)",
                ("gift-" + secrets.token_hex(8), uin, body.to, text, now, next_rev(c), g["id"]),
            )
            row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
            msg = msg_json(c, row)
            push_message(c, row, [body.to])
    if msg:
        await manager.send(body.to, {"type": "message", "message": msg})
        await manager.send(uin, {"type": "message", "message": msg})
    return {"balance": bal, "message": msg}


@app.get("/api/users/{other}/gifts")
def user_gifts(other: int, authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        rows = c.execute("SELECT * FROM user_gifts WHERE to_uin=? ORDER BY id DESC LIMIT 100", (other,)).fetchall()
        out = []
        for r in rows:
            g = GIFT_BY_ID.get(r["gift"]) or {"id": r["gift"], "emoji": "🎁", "name": "Подарок", "price": 0}
            f = None if (r["anonymous"] and other != uin and r["from_uin"] != uin) else \
                c.execute("SELECT * FROM users WHERE uin=?", (r["from_uin"],)).fetchone()
            out.append({"gift": g, "text": r["text"] or "", "ts": r["ts"],
                        "from": user_json(f, c=c) if f else None})
        return out


class ViewsIn(BaseModel):
    ids: List[int] = []


@app.post("/api/messages/views")
def post_views(body: ViewsIn, authorization: Optional[str] = Header(None)) -> dict:
    """The phone reports channel posts it showed; returns fresh view counters."""
    uin = require_uin(authorization)
    out = {}
    now = now_ms()
    with conn() as c:
        for mid in list(dict.fromkeys(body.ids))[:200]:
            r = c.execute(
                "SELECT m.id, m.group_id, m.from_uin, g.kind FROM messages m JOIN groups g ON g.id=m.group_id"
                " WHERE m.id=?", (mid,),
            ).fetchone()
            if not r or r["kind"] != "channel" or not is_member(c, r["group_id"], uin):
                continue
            c.execute("INSERT OR IGNORE INTO post_views(msg_id, uin, ts) VALUES(?,?,?)", (mid, uin, now))
            out[str(mid)] = c.execute("SELECT COUNT(*) AS n FROM post_views WHERE msg_id=?", (mid,)).fetchone()["n"]
    return out


class VoteIn(BaseModel):
    options: List[int] = []


@app.post("/api/polls/{mid}/vote")
async def poll_vote(mid: int, body: VoteIn, authorization: Optional[str] = Header(None)) -> dict:
    """Vote (or retract with an empty list). Returns the message with my choice."""
    uin = require_uin(authorization)
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=? AND kind='poll'", (mid,)).fetchone()
        p = c.execute("SELECT * FROM polls WHERE msg_id=?", (mid,)).fetchone()
        if not r or not p:
            raise HTTPException(404, "Опрос не найден")
        if r["group_id"]:
            if not is_member(c, r["group_id"], uin):
                raise HTTPException(403, "Вы не участник этого чата")
        elif uin not in (r["from_uin"], r["to_uin"]):
            raise HTTPException(403, "Нет доступа")
        if p["closed"]:
            raise HTTPException(400, "Опрос завершён")
        n = len(json.loads(p["options"]))
        opts = sorted({o for o in body.options if 0 <= o < n})
        if not p["multiple"]:
            opts = opts[:1]
        c.execute("DELETE FROM poll_votes WHERE msg_id=? AND uin=?", (mid, uin))
        now = now_ms()
        for o in opts:
            c.execute("INSERT INTO poll_votes(msg_id, uin, opt, ts) VALUES(?,?,?,?)", (mid, uin, o, now))
        c.execute("UPDATE messages SET rev=? WHERE id=?", (next_rev(c), mid))
        targets, payload = build_update(c, mid)
        mine = msg_json(c, c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone(), uin)
    await manager.send_many([t for t in targets if t != uin], payload)
    return mine


@app.get("/api/polls/{mid}/voters")
def poll_voters(mid: int, authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=? AND kind='poll'", (mid,)).fetchone()
        p = c.execute("SELECT * FROM polls WHERE msg_id=?", (mid,)).fetchone()
        if not r or not p or p["anonymous"]:
            raise HTTPException(404, "Голоса скрыты")
        if r["group_id"] and not is_member(c, r["group_id"], uin):
            raise HTTPException(403, "Нет доступа")
        rows = c.execute(
            "SELECT v.opt, u.* FROM poll_votes v JOIN users u ON u.uin=v.uin WHERE v.msg_id=? ORDER BY v.ts", (mid,)
        ).fetchall()
        return [{"opt": x["opt"], "user": user_json(x, c=c)} for x in rows]


@app.get("/api/groups/{gid}/posts")
def public_posts(gid: int, limit: int = Query(5), authorization: Optional[str] = Header(None)) -> list:
    """Latest posts of an open channel / group: shown before subscribing."""
    uin = require_uin(authorization)
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if not g or not (g["public"] or g["username"] or is_member(c, gid, uin)):
            raise HTTPException(404, "Чат не найден")
        rows = c.execute(
            "SELECT * FROM messages WHERE group_id=? AND deleted=0 AND kind NOT IN ('system','gone')"
            " ORDER BY id DESC LIMIT ?", (gid, max(1, min(limit, 100))),
        ).fetchall()
        return [msg_json(c, r, uin) for r in rows]


@app.get("/api/groups/{gid}/log")
def group_log_endpoint(gid: int, authorization: Optional[str] = Header(None)) -> list:
    """Recent actions: who subscribed, who left, who was removed (admins only)."""
    uin = require_uin(authorization)
    with conn() as c:
        require_group_admin(c, gid, uin)
        rows = c.execute(
            "SELECT l.*, u.uin AS u_uin FROM group_log l LEFT JOIN users u ON u.uin=l.uin"
            " WHERE l.gid=? ORDER BY l.ts DESC LIMIT 200", (gid,),
        ).fetchall()
        out = []
        for r in rows:
            u = c.execute("SELECT * FROM users WHERE uin=?", (r["uin"],)).fetchone()
            a = c.execute("SELECT * FROM users WHERE uin=?", (r["actor"],)).fetchone() if r["actor"] else None
            out.append({
                "action": r["action"], "ts": r["ts"],
                "user": user_json(u, c=c) if u else {"uin": r["uin"], "name": "Удалённый аккаунт"},
                "actor": user_json(a, c=c) if a else None,
            })
        return out


@app.get("/api/groups/{gid}/stats")
def group_stats(gid: int, days: int = Query(14), authorization: Optional[str] = Header(None)) -> dict:
    """Channel statistics: subscribers, growth, views, reactions, best posts."""
    uin = require_uin(authorization)
    days = max(1, min(days, 60))
    with conn() as c:
        require_group_admin(c, gid, uin)
        day = 86_400_000
        now = now_ms()
        start = (now // day - days + 1) * day
        members = c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (gid,)).fetchone()["n"]
        series = []
        for i in range(days):
            a, b = start + i * day, start + (i + 1) * day
            j = c.execute("SELECT COUNT(*) AS n FROM group_log WHERE gid=? AND action IN ('join','added') AND ts>=? AND ts<?",
                          (gid, a, b)).fetchone()["n"]
            l = c.execute("SELECT COUNT(*) AS n FROM group_log WHERE gid=? AND action IN ('leave','kick','ban') AND ts>=? AND ts<?",
                          (gid, a, b)).fetchone()["n"]
            v = c.execute("SELECT COUNT(*) AS n FROM post_views pv JOIN messages m ON m.id=pv.msg_id"
                          " WHERE m.group_id=? AND pv.ts>=? AND pv.ts<?", (gid, a, b)).fetchone()["n"]
            series.append({"day": a, "joined": j, "left": l, "views": v})
        posts = c.execute(
            "SELECT m.*, (SELECT COUNT(*) FROM post_views pv WHERE pv.msg_id=m.id) AS v,"
            " (SELECT COUNT(*) FROM reactions r WHERE r.msg_id=m.id) AS rc FROM messages m"
            " WHERE m.group_id=? AND m.deleted=0 AND m.kind NOT IN ('system','bot','gone') ORDER BY m.id DESC LIMIT 30",
            (gid,),
        ).fetchall()
        recent = [{"id": p["id"], "text": p["text"][:120], "kind": p["kind"], "ts": p["ts"], "views": p["v"],
                   "reactions": p["rc"]} for p in posts]
        total_views = c.execute("SELECT COUNT(*) AS n FROM post_views pv JOIN messages m ON m.id=pv.msg_id WHERE m.group_id=?",
                                (gid,)).fetchone()["n"]
        total_reacts = c.execute("SELECT COUNT(*) AS n FROM reactions r JOIN messages m ON m.id=r.msg_id WHERE m.group_id=?",
                                 (gid,)).fetchone()["n"]
        top_r = c.execute("SELECT r.emoji, COUNT(*) AS n FROM reactions r JOIN messages m ON m.id=r.msg_id"
                          " WHERE m.group_id=? GROUP BY r.emoji ORDER BY n DESC LIMIT 8", (gid,)).fetchall()
        avg = round(sum(p["views"] for p in recent) / len(recent)) if recent else 0
        return {
            "members": members, "views": total_views, "reactions": total_reacts, "avg_views": avg,
            "joined": sum(x["joined"] for x in series), "left": sum(x["left"] for x in series),
            "series": series, "posts": recent,
            "top_reactions": [{"emoji": r["emoji"], "count": r["n"]} for r in top_r],
        }


@app.post("/api/groups")
async def create_group(body: GroupCreateIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    name = body.name.strip()
    kind = "channel" if body.kind == "channel" else "group"
    if not name:
        raise HTTPException(400, "Введите название")
    members = [m for m in dict.fromkeys(body.members) if m != uin][: MAX_GROUP - 1]
    now = now_ms()
    with conn() as c:
        cur = c.execute(
            "INSERT INTO groups(name, owner, created, kind, about, public, ttl) VALUES(?,?,?,?,?,?,?)",
            (name, uin, now, kind, (body.about or "").strip()[:255], int(body.public),
             0 if kind == "channel" else  # channel posts stay
             (_col(c.execute("SELECT default_ttl FROM users WHERE uin=?", (uin,)).fetchone(), "default_ttl", 0) or 0)),
        )
        gid = cur.lastrowid
        c.execute("INSERT INTO group_members(gid, uin, joined, role) VALUES(?,?,?, 'owner')", (gid, uin, now))
        for m in members:
            if c.execute("SELECT 1 FROM users WHERE uin=? AND banned=0", (m,)).fetchone() and not is_blocked(c, m, uin):
                c.execute("INSERT OR IGNORE INTO group_members(gid, uin, joined) VALUES(?,?,?)", (gid, m, now))
        what = "канал" if kind == "channel" else "группу"
        sys_row = insert_system(c, gid, uin, f"{uname(c, uin)} создал(а) {what} «{name}»")
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        result = group_json(c, g)
    await push_group(gid, sys_rows=[sys_row])
    return result


@app.post("/api/groups/{gid}")
async def edit_group(gid: int, body: GroupEditIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Введите название")
    with conn() as c:
        g = require_group_admin(c, gid, uin)
        sys_rows = []
        if name != g["name"]:
            c.execute("UPDATE groups SET name=? WHERE id=?", (name, gid))
            sys_rows.append(insert_system(c, gid, uin, f"{uname(c, uin)} изменил(а) название на «{name}»"))
        if body.about is not None:
            c.execute("UPDATE groups SET about=? WHERE id=?", (body.about.strip()[:255], gid))
        if body.public is not None:
            c.execute("UPDATE groups SET public=? WHERE id=?", (int(body.public), gid))
        if body.username is not None:
            un = check_username(c, body.username, group_id=gid)
            c.execute("UPDATE groups SET username=? WHERE id=?", (un, gid))
        result = group_json(c, c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
    await push_group(gid, sys_rows=sys_rows)
    return result


@app.post("/api/groups/{gid}/avatar")
async def group_avatar(gid: int, request: Request, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    data = await request.body()
    _check_jpeg(data)
    with conn() as c:
        g = require_group_admin(c, gid, uin)
        with open(os.path.join(AVATAR_DIR, f"g{gid}.jpg"), "wb") as f:
            f.write(data)
        CLUSTER.push_file("avatars", f"g{gid}.jpg", os.path.join(AVATAR_DIR, f"g{gid}.jpg"))
        c.execute("UPDATE groups SET avatar_url=? WHERE id=?", (f"/avatars/g{gid}.jpg?v={now_ms()}", gid))
        sys_row = insert_system(c, gid, uin, f"{uname(c, uin)} изменил(а) фото")
        result = group_json(c, c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
    await push_group(gid, sys_rows=[sys_row])
    return result


@app.post("/api/groups/{gid}/members")
async def add_members(gid: int, body: GroupAddIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    now = now_ms()
    sys_rows = []
    with conn() as c:
        g = require_group(c, gid, uin)
        if g["kind"] == "channel" and not is_admin(c, gid, uin):
            raise HTTPException(403, "Добавлять подписчиков могут только администраторы")
        if group_settings(g)["only_admins_add"] and not is_admin(c, gid, uin):
            raise HTTPException(403, "Добавлять участников могут только администраторы")
        count = c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (gid,)).fetchone()["n"]
        for m in dict.fromkeys(body.members):
            if count >= MAX_GROUP:
                break
            if is_member(c, gid, m):
                continue
            if not c.execute("SELECT 1 FROM users WHERE uin=? AND banned=0", (m,)).fetchone() or is_blocked(c, m, uin):
                continue
            if is_group_banned(c, gid, m):
                if not is_admin(c, gid, uin):
                    continue
                c.execute("DELETE FROM group_bans WHERE gid=? AND uin=?", (gid, m))
            c.execute("INSERT INTO group_members(gid, uin, joined) VALUES(?,?,?)", (gid, m, now))
            log_group(c, gid, m, "added", uin)
            count += 1
            if g["kind"] != "channel":
                sys_rows.append(insert_system(c, gid, uin, f"{uname(c, uin)} добавил(а) {uname(c, m)}"))
                if group_settings(g)["welcome"]:
                    sys_rows.append(insert_bot(c, gid, f"👋 Добро пожаловать, {uname(c, m)}!"))
        result = group_json(c, c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
    await push_group(gid, sys_rows=sys_rows)
    return result


@app.post("/api/groups/{gid}/join")
async def join_group(gid: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    sys_rows = []
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if not g or not g["public"]:
            raise HTTPException(404, "Чат не найден")
        if is_group_banned(c, gid, uin):
            raise HTTPException(403, "Вы заблокированы в этом чате")
        if not is_member(c, gid, uin):
            c.execute("INSERT INTO group_members(gid, uin, joined) VALUES(?,?,?)", (gid, uin, now_ms()))
            log_group(c, gid, uin, "join")
            if g["kind"] != "channel":
                sys_rows.append(insert_system(c, gid, uin, f"{uname(c, uin)} присоединился(-ась) к группе"))
                if group_settings(g)["welcome"]:
                    sys_rows.append(insert_bot(c, gid, f"👋 Добро пожаловать, {uname(c, uin)}!"))
        result = group_view(c, g, uin)
    await push_group(gid, sys_rows=sys_rows)
    return result


async def leave_group(gid: int, who: int, actor: int, system_text: str, deleted_user: bool = False) -> None:
    sys_row = None
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        if not g:
            return
        name = uname(c, who) if not deleted_user else "Пользователь"
        c.execute("DELETE FROM group_members WHERE gid=? AND uin=?", (gid, who))
        log_group(c, gid, who, "leave" if actor == who else "kick", actor)
        left = group_members(c, gid)
        if not left:  # last member left: remove the chat completely
            c.execute("DELETE FROM reactions WHERE msg_id IN (SELECT id FROM messages WHERE group_id=?)", (gid,))
            c.execute("DELETE FROM messages WHERE group_id=?", (gid,))
            c.execute("DELETE FROM group_bans WHERE gid=?", (gid,))
            c.execute("DELETE FROM groups WHERE id=?", (gid,))
        else:
            if g["owner"] == who:
                nxt = c.execute(
                    "SELECT uin FROM group_members WHERE gid=? ORDER BY CASE role WHEN 'admin' THEN 0 ELSE 1 END, joined"
                    " LIMIT 1", (gid,),
                ).fetchone()["uin"]
                c.execute("UPDATE groups SET owner=? WHERE id=?", (nxt, gid))
                c.execute("UPDATE group_members SET role='owner' WHERE gid=? AND uin=?", (gid, nxt))
            if g["kind"] != "channel":
                if actor == who:
                    sys_row = insert_system(c, gid, who, f"{name} {system_text}")
                else:
                    sys_row = insert_system(c, gid, actor, f"{uname(c, actor)} удалил(а) {name}")
    await manager.send(who, {"type": "group_gone", "id": gid})
    await push_group(gid, sys_rows=[sys_row] if sys_row is not None else [])


@app.delete("/api/groups/{gid}/members/{member}")
async def remove_member(gid: int, member: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    if member != uin:
        await moderate_api(gid, uin, member, "kick", 0)
        return {"ok": True}
    with conn() as c:
        require_group(c, gid, uin)
    await leave_group(gid, member, actor=uin, system_text="покинул(а) группу")
    return {"ok": True}


@app.post("/api/groups/{gid}/settings")
async def group_settings_endpoint(gid: int, body: GroupSettingsIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        g = require_group_admin(c, gid, uin)
        cur = group_settings(g)
        for k, v in body.settings.items():
            if k not in GROUP_DEFAULTS:
                continue
            if k == "slow_mode":
                try:
                    cur[k] = max(0, min(int(v), 3600))
                except (TypeError, ValueError):
                    pass
            else:
                cur[k] = bool(v)
        c.execute("UPDATE groups SET settings=? WHERE id=?", (json.dumps(cur), gid))
        if body.bot_name is not None and body.bot_name.strip():
            c.execute("UPDATE groups SET bot_name=? WHERE id=?", (body.bot_name.strip()[:40], gid))
        result = group_json(c, c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
    await push_group(gid)
    return result


async def delete_group_everywhere(gid: int) -> Optional[str]:
    """Removes a group / channel with everything in it; every member's app drops it."""
    with conn() as c:
        g = c.execute("SELECT name, kind FROM groups WHERE id=?", (gid,)).fetchone()
        if not g:
            return None
        members = group_members(c, gid)
        ids = [r["id"] for r in c.execute("SELECT id FROM messages WHERE group_id=?", (gid,)).fetchall()]
        for i in range(0, len(ids), 500):
            part = ids[i:i + 500]
            q = ",".join("?" * len(part))
            for t in ("reactions", "post_views", "poll_votes", "polls"):
                c.execute(f"DELETE FROM {t} WHERE msg_id IN ({q})", part)
        c.execute("DELETE FROM messages WHERE group_id=?", (gid,))
        for t, col in (("group_members", "gid"), ("group_bans", "gid"), ("group_log", "gid")):
            c.execute(f"DELETE FROM {t} WHERE {col}=?", (gid,))
        c.execute("UPDATE users SET channel_id=NULL WHERE channel_id=?", (gid,))
        c.execute("DELETE FROM groups WHERE id=?", (gid,))
    for name in (f"g{gid}.jpg", f"b{gid}.jpg"):
        try:
            os.remove(os.path.join(AVATAR_DIR, name))
        except OSError:
            pass
        CLUSTER.delete_file("avatars", name)
    await manager.send_many(members, {"type": "group_gone", "id": gid})
    return g["name"]


@app.delete("/api/groups/{gid}")
async def delete_group(gid: int, authorization: Optional[str] = Header(None)) -> dict:
    """Only the creator deletes his group / channel (for everybody)."""
    uin = require_uin(authorization)
    with conn() as c:
        g = c.execute("SELECT owner FROM groups WHERE id=?", (gid,)).fetchone()
        if not g:
            raise HTTPException(404, "Не найдено")
        if g["owner"] != uin:
            raise HTTPException(403, "Удалить может только создатель")
    await delete_group_everywhere(gid)
    return {"ok": True}


@app.post("/api/groups/{gid}/bot_avatar")
async def group_bot_avatar(gid: int, request: Request, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    data = await request.body()
    _check_jpeg(data)
    with conn() as c:
        require_group_admin(c, gid, uin)
        with open(os.path.join(AVATAR_DIR, f"b{gid}.jpg"), "wb") as f:
            f.write(data)
        CLUSTER.push_file("avatars", f"b{gid}.jpg", os.path.join(AVATAR_DIR, f"b{gid}.jpg"))
        c.execute("UPDATE groups SET bot_avatar=? WHERE id=?", (f"/avatars/b{gid}.jpg?v={now_ms()}", gid))
        result = group_json(c, c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
    await push_group(gid)
    return result


@app.post("/api/chats/clear")
async def clear_chat(body: ClearIn, authorization: Optional[str] = Header(None)) -> dict:
    """Deletes a chat's history for both sides (private chat) or for every member (group: creator only)."""
    uin = require_uin(authorization)
    events = []  # (target uin, peer key as the target sees it, upto)
    sys_row = None
    gid = body.group
    with conn() as c:
        if gid is not None:
            g = require_group(c, gid, uin)
            if g["owner"] != uin:
                raise HTTPException(403, "Очистить историю у всех может только создатель")
            upto = c.execute("SELECT COALESCE(MAX(id),0) AS m FROM messages WHERE group_id=?", (gid,)).fetchone()["m"]
            c.execute("DELETE FROM reactions WHERE msg_id IN (SELECT id FROM messages WHERE group_id=?)", (gid,))
            c.execute("DELETE FROM messages WHERE group_id=?", (gid,))
            for m in group_members(c, gid):
                events.append((m, -gid, upto))
            sys_row = insert_bot(c, gid, f"🧹 {uname(c, uin)} очистил(а) историю чата")
        elif body.to is not None:
            other = body.to
            upto = c.execute(
                "SELECT COALESCE(MAX(id),0) AS m FROM messages WHERE group_id IS NULL AND"
                " ((from_uin=? AND to_uin=?) OR (from_uin=? AND to_uin=?))",
                (uin, other, other, uin),
            ).fetchone()["m"]
            if body.for_all:
                c.execute(
                    "DELETE FROM reactions WHERE msg_id IN (SELECT id FROM messages WHERE group_id IS NULL AND"
                    " ((from_uin=? AND to_uin=?) OR (from_uin=? AND to_uin=?)))",
                    (uin, other, other, uin),
                )
                c.execute(
                    "DELETE FROM messages WHERE group_id IS NULL AND"
                    " ((from_uin=? AND to_uin=?) OR (from_uin=? AND to_uin=?))",
                    (uin, other, other, uin),
                )
                if other != uin:
                    events.append((other, uin, upto))
            events.append((uin, other, upto))
        else:
            raise HTTPException(400, "Не указан чат")
        for target, peer, up in events:
            c.execute("INSERT INTO clears(uin, peer, upto, rev) VALUES(?,?,?,?)", (target, peer, up, next_rev(c)))
        if gid is None and body.to is not None and not body.for_all:
            other_up = c.execute(
                "SELECT MAX(upto) AS m FROM clears WHERE uin=? AND peer=?", (body.to, uin)
            ).fetchone()["m"]
            if other_up:
                purge_private(c, uin, body.to, min(other_up, upto))
        sys_msg = msg_json(c, sys_row) if sys_row is not None else None
    for target, peer, up in events:
        await manager.send(target, {"type": "chat_cleared", "id": peer, "upto": up})
    if sys_msg is not None:
        await manager.send_many([t for t, _, _ in events], {"type": "message", "message": sys_msg})
    return {"ok": True}


def chat_ttl_of(c: sqlite3.Connection, sender: int, to: Optional[int], gid: Optional[int]) -> int:
    if gid:
        g = c.execute("SELECT ttl FROM groups WHERE id=?", (gid,)).fetchone()
        return (g["ttl"] if g else 0) or 0
    if not to or to == sender:  # "Saved messages" are never auto-deleted
        return 0
    a, b = min(sender, to), max(sender, to)
    r = c.execute("SELECT ttl FROM chat_ttl WHERE a=? AND b=?", (a, b)).fetchone()
    if r:  # the chat has its own timer (0 = switched off for this chat)
        return r["ttl"]
    u = c.execute("SELECT default_ttl FROM users WHERE uin=?", (sender,)).fetchone()
    return (_col(u, "default_ttl", 0) or 0) if u else 0


class TtlIn(BaseModel):
    to: Optional[int] = None
    group: Optional[int] = None
    seconds: int = 0


def _ttl_label(sec: int) -> str:
    return {0: "выключено", 3600: "1 час", 86400: "1 день", 2 * 86400: "2 дня", 7 * 86400: "1 неделя",
            30 * 86400: "1 месяц"}.get(sec, f"{sec} с")


class DefaultTtlIn(BaseModel):
    seconds: int = DEFAULT_TTL


@app.get("/api/me/ttl")
def get_default_ttl(authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        u = c.execute("SELECT default_ttl FROM users WHERE uin=?", (uin,)).fetchone()
        return {"seconds": (_col(u, "default_ttl", 0) or 0) if u else 0}


@app.post("/api/me/ttl")
def set_default_ttl(body: DefaultTtlIn, authorization: Optional[str] = Header(None)) -> dict:
    """Settings → auto-delete for all my chats (0 = off)."""
    uin = require_uin(authorization)
    sec = body.seconds if body.seconds in TTL_CHOICES else DEFAULT_TTL
    with conn() as c:
        c.execute("UPDATE users SET default_ttl=? WHERE uin=?", (sec, uin))
    return {"seconds": sec}


@app.get("/api/chats/ttl")
def get_ttl(to: Optional[int] = None, group: Optional[int] = None, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        return {"seconds": chat_ttl_of(c, uin, to, group)}


@app.post("/api/chats/ttl")
async def set_ttl(body: TtlIn, authorization: Optional[str] = Header(None)) -> dict:
    """Auto-delete timer for a chat: new messages vanish for everybody after the given time."""
    uin = require_uin(authorization)
    sec = body.seconds if body.seconds in TTL_CHOICES else 0
    with conn() as c:
        me_name = c.execute("SELECT name FROM users WHERE uin=?", (uin,)).fetchone()["name"]
        if body.group:
            if not is_admin(c, body.group, uin):
                raise HTTPException(403, "Только администраторы")
            c.execute("UPDATE groups SET ttl=? WHERE id=?", (sec, body.group))
        elif body.to:
            a, b = min(uin, body.to), max(uin, body.to)
            c.execute("INSERT OR REPLACE INTO chat_ttl(a, b, ttl) VALUES(?,?,?)", (a, b, sec))
        else:
            raise HTTPException(400, "Нет чата")
    text = f"{me_name} установил(а) автоудаление: {_ttl_label(sec)}" if sec else f"{me_name} выключил(а) автоудаление"
    if body.group:
        with conn() as c:
            sys_row = insert_system(c, body.group, uin, text)
        await push_group(body.group, sys_rows=[sys_row])
    else:
        await post_private_system(uin, body.to, text)
    return {"seconds": sec}


async def post_private_system(a: int, b: int, text: str) -> None:
    with conn() as c:
        cur = c.execute(
            "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, rev) VALUES(?,?,?,?,?,'system',?)",
            ("sys-" + secrets.token_hex(6), a, b, text, now_ms(), next_rev(c)),
        )
        row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
        msg = msg_json(c, row)
    await manager.send_many([a, b], {"type": "message", "message": msg})


async def expire_messages() -> None:
    """Auto-delete: expired messages disappear for everybody (kind 'gone' tells the phones to drop them)."""
    now = now_ms()
    with conn() as c:
        rows = c.execute("SELECT id FROM messages WHERE expire_at>0 AND expire_at<=? AND kind<>'gone'", (now,)).fetchall()
        updates = []
        for r in rows:
            c.execute(
                "UPDATE messages SET text='', kind='gone', deleted=1, att_id=NULL, att_name=NULL, att_size=0,"
                " att_mime=NULL, att_dur=0, rev=? WHERE id=?",
                (next_rev(c), r["id"]),
            )
            c.execute("DELETE FROM reactions WHERE msg_id=?", (r["id"],))
            updates.append(build_update(c, r["id"]))
    for targets, payload in updates:
        await manager.send_many(targets, payload)


def purge_old() -> int:
    """The server is not an archive: drop messages older than KEEP_DAYS (phones keep their copy)."""
    if KEEP_DAYS <= 0:
        return 0
    limit = now_ms() - KEEP_DAYS * 86_400_000
    with conn() as c:
        c.execute("DELETE FROM reactions WHERE msg_id IN (SELECT id FROM messages WHERE ts<?)", (limit,))
        n = c.execute("DELETE FROM messages WHERE ts<?", (limit,)).rowcount
        c.execute("DELETE FROM qr_logins WHERE created<?", (now_ms() - 600_000,))
        c.execute("DELETE FROM link_cache WHERE ts<?", (now_ms() - 7 * 86_400_000,))
    return n


async def expire_loop() -> None:
    while True:
        await asyncio.sleep(30)
        if not CLUSTER.leader:
            continue
        try:
            await expire_messages()
        except Exception as e:  # noqa: BLE001
            print("expire failed:", e)


# ---------------------------------------------------------- link previews
_OG_RE = re.compile(r'<meta[^>]+(?:property|name)=["\'](og:[a-z:_]+|twitter:[a-z:_]+|description)["\'][^>]*>', re.I)


def _meta_content(tag: str) -> str:
    m = re.search(r'content=["\']([^"\']*)["\']', tag, re.I)
    return html.unescape(m.group(1)).strip() if m else ""


def fetch_preview(url: str) -> dict:
    import urllib.request
    from urllib.parse import urljoin, urlparse
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        return {}
    try:
        import ipaddress, socket
        ip = ipaddress.ip_address(socket.gethostbyname(u.hostname))
        if ip.is_private or ip.is_loopback or ip.is_link_local:
            return {}
    except Exception:  # noqa: BLE001
        return {}
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; ICQNewBot/1.0)",
                                               "Accept-Language": "ru,en;q=0.8"})
    with urllib.request.urlopen(req, timeout=5) as r:
        ctype = r.headers.get("content-type", "")
        if "html" not in ctype:
            return {"url": url, "site": u.hostname, "title": u.hostname}
        raw = r.read(400_000).decode(r.headers.get_content_charset() or "utf-8", "ignore")
    d: dict = {"url": url, "site": u.hostname}
    for tag in _OG_RE.finditer(raw):
        key = tag.group(1).lower()
        val = _meta_content(tag.group(0))
        if not val:
            continue
        if key in ("og:title", "twitter:title") and "title" not in d:
            d["title"] = val[:200]
        elif key in ("og:description", "twitter:description", "description") and "description" not in d:
            d["description"] = val[:300]
        elif key in ("og:image", "twitter:image", "og:image:url") and "image" not in d:
            d["image"] = urljoin(url, val)
        elif key == "og:site_name":
            d["site"] = val[:60]
    if "title" not in d:
        m = re.search(r"<title[^>]*>(.*?)</title>", raw, re.I | re.S)
        if m:
            d["title"] = html.unescape(m.group(1)).strip()[:200]
    return d


ICQ_LINK = re.compile(r"^(?:https?://)?(?:www\.)?([a-z0-9.-]+)(?::\d+)?/(?:@([A-Za-z0-9_]{3,32})|g/(\d+))/?$", re.I)


def own_link_preview(url: str, host: str) -> Optional[dict]:
    """Links to people / groups / channels inside ICQ New: a card with the avatar, name and members."""
    m = ICQ_LINK.match(url)
    if not m:
        return None
    link_host = m.group(1).lower()
    own = {h.lower() for h in (os.environ.get("ICQ_DOMAIN", "icq-new.online"), "icq-new.online", "icq-new.ru", host.split(":")[0]) if h}
    if link_host not in own:
        return None
    with conn() as c:
        u = g = None
        if m.group(2):
            u = c.execute("SELECT * FROM users WHERE lower(username)=lower(?) AND banned=0", (m.group(2),)).fetchone()
            if not u:
                g = c.execute("SELECT * FROM groups WHERE lower(username)=lower(?)", (m.group(2),)).fetchone()
        else:
            g = c.execute("SELECT * FROM groups WHERE id=? AND public=1", (int(m.group(3)),)).fetchone()
        if u:
            return {"url": url, "site": "ICQ New", "type": "user", "peer": u["uin"], "title": u["name"],
                    "description": (u["about"] or "") or ("@" + u["username"] if u["username"] else ""),
                    "image": visible_avatar(u, CURRENT_VIEWER.get(), c), "verified": bool(u["verified"])}
        if g:
            n = c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (g["id"],)).fetchone()["n"]
            ch = g["kind"] == "channel"
            return {"url": url, "site": "ICQ New", "type": "channel" if ch else "group", "peer": -g["id"],
                    "title": g["name"], "description": g["about"] or "", "image": g["avatar_url"],
                    "count": n, "verified": bool(_col(g, "verified", 0))}
    return {"url": url, "site": "ICQ New", "type": "missing", "title": "Не найдено"}


@app.get("/api/preview")
async def link_preview(request: Request, url: str = Query(...), authorization: Optional[str] = Header(None)) -> dict:
    require_uin(authorization)
    url = url.strip()[:1000]
    own = own_link_preview(url, request.headers.get("host", ""))
    if own is not None:
        return own
    with conn() as c:
        r = c.execute("SELECT data FROM link_cache WHERE url=?", (url,)).fetchone()
    if r:
        return json.loads(r["data"])
    try:
        d = await asyncio.to_thread(fetch_preview, url)
    except Exception:  # noqa: BLE001
        d = {}
    with conn() as c:
        c.execute("INSERT OR REPLACE INTO link_cache(url, data, ts) VALUES(?,?,?)", (url, json.dumps(d), now_ms()))
    return d


# ---------------------------------------------------------- "is this number in ICQ?"
@app.get("/api/lookup")
def lookup_phone(phone: str = Query(...), authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    key = digits(phone)[-8:]
    if len(key) < 7:
        return {"found": False}
    with conn() as c:
        row = c.execute("SELECT * FROM users WHERE phone_key=? AND banned=0", (key,)).fetchone()
        if not row or (not row["searchable"] and not has_contact(row["uin"], uin, c)):
            return {"found": False}
        return {"found": True, "user": user_json(row, c=c)}


# ---------------------------------------------------------- QR login for the web version
@app.post("/api/qr/new")
def qr_new(device: str = Query("")) -> dict:
    code = secrets.token_urlsafe(18)
    with conn() as c:
        c.execute("INSERT INTO qr_logins(code, created) VALUES(?,?)", (code, now_ms()))
        if device:
            c.execute("UPDATE qr_logins SET device=? WHERE code=?", (device[:60], code))
    return {"code": code, "qr": "icqnew-login:" + code}


@app.get("/api/qr/{code}")
def qr_poll(code: str) -> dict:
    with conn() as c:
        r = c.execute("SELECT * FROM qr_logins WHERE code=?", (code,)).fetchone()
        if not r or now_ms() - r["created"] > 300_000:
            raise HTTPException(404, "QR-код устарел")
        if not r["token"]:
            return {"ok": False}
        c.execute("DELETE FROM qr_logins WHERE code=?", (code,))
        u = c.execute("SELECT * FROM users WHERE uin=?", (r["uin"],)).fetchone()
        return {"ok": True, "token": r["token"], "user": me_json(u)}


@app.get("/api/qr/{code}/svg")
def qr_svg(code: str):
    """QR picture for the web login page (drawn on the server: no CDN needed)."""
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        raise HTTPException(503, "pip install qrcode")
    img = qrcode.make("icqnew-login:" + code, image_factory=qrcode.image.svg.SvgPathImage, box_size=12, border=2)
    import io
    buf = io.BytesIO()
    img.save(buf)
    return Response(buf.getvalue(), media_type="image/svg+xml", headers={"Cache-Control": "no-store"})


_qr_devices: Dict[str, str] = {}


class QrApproveIn(BaseModel):
    code: str


@app.post("/api/qr/approve")
async def qr_approve(body: QrApproveIn, request: Request, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    code = body.code.strip().removeprefix("icqnew-login:")
    with conn() as c:
        r = c.execute("SELECT * FROM qr_logins WHERE code=?", (code,)).fetchone()
        if not r or now_ms() - r["created"] > 300_000 or r["token"]:
            raise HTTPException(404, "QR-код устарел, обновите страницу")
        token = secrets.token_hex(32)
        qrow = c.execute("SELECT device FROM qr_logins WHERE code=?", (code,)).fetchone()
        phone_dev = (qrow["device"] if qrow else "") or ""
        agent = (request.headers.get("x-web-agent") or "Web")[:60]
        label = phone_dev if phone_dev else "Web · " + agent
        c.execute(
            "INSERT INTO tokens(token, uin, created, device, last_used) VALUES(?,?,?,?,?)",
            (token, uin, now_ms(), label, now_ms()),
        )
        c.execute("UPDATE qr_logins SET token=?, uin=? WHERE code=?", (token, uin, code))
    asyncio.create_task(announce_login(uin, (phone_dev + " (вход по QR-коду)") if phone_dev else "Веб-версия (вход по QR-коду)", "", True))
    return {"ok": True}


def purge_private(c: sqlite3.Connection, a: int, b: int, upto: int) -> None:
    """Both sides deleted the chat up to [upto]: remove those rows for real."""
    where = ("group_id IS NULL AND id<=? AND ((from_uin=? AND to_uin=?) OR (from_uin=? AND to_uin=?))")
    args = (upto, a, b, b, a)
    c.execute(f"DELETE FROM reactions WHERE msg_id IN (SELECT id FROM messages WHERE {where})", args)
    c.execute(f"DELETE FROM messages WHERE {where}", args)


def cleanup_storage() -> dict:
    """Removes files nobody can reach any more and compacts the database."""
    removed, freed = 0, 0
    day_ago = now_ms() - 86_400_000
    with conn() as c:
        # sessions nobody used for 120 days (old installs, lost phones) disappear from «Устройства»
        c.execute("DELETE FROM tokens WHERE max(COALESCE(last_used,0), created) < ?", (now_ms() - 120 * 86_400_000,))
        rows = c.execute(
            "SELECT id, path, size FROM files f WHERE f.created<? AND"
            " NOT EXISTS (SELECT 1 FROM messages m WHERE m.att_id=f.id) AND"
            " NOT EXISTS (SELECT 1 FROM stories s WHERE s.att_id=f.id)",
            (day_ago,),
        ).fetchall()
        for r in rows:
            try:
                if r["path"] and os.path.exists(r["path"]):
                    freed += os.path.getsize(r["path"])
                    os.remove(r["path"])
            except OSError:
                pass
            c.execute("DELETE FROM files WHERE id=?", (r["id"],))
            removed += 1
        c.execute("DELETE FROM story_views WHERE story NOT IN (SELECT id FROM stories)")
        c.execute("DELETE FROM story_comments WHERE story NOT IN (SELECT id FROM stories)")
        c.execute("DELETE FROM reactions WHERE msg_id NOT IN (SELECT id FROM messages)")
        for t in ("post_views", "poll_votes", "polls"):
            c.execute(f"DELETE FROM {t} WHERE msg_id NOT IN (SELECT id FROM messages)")
        c.execute("DELETE FROM group_log WHERE ts<? OR gid NOT IN (SELECT id FROM groups)", (now_ms() - 90 * 86_400_000,))
    c2 = sqlite3.connect(DB_PATH, timeout=30)
    try:
        c2.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        c2.execute("VACUUM")
    except sqlite3.Error:
        pass
    finally:
        c2.close()
    return {"files": removed, "freed": freed}


def sweep_local_files() -> int:
    """Cluster: copies of files that no longer exist in the database are removed from this server's disk."""
    removed = 0
    cutoff = time.time() - 2 * 86400
    try:
        names = [n for n in os.listdir(UPLOAD_DIR) if not n.endswith(".part")]
    except OSError:
        return 0
    with conn() as c:
        for i in range(0, len(names), 500):
            part = names[i:i + 500]
            known = {r["id"] for r in c.execute(
                "SELECT id FROM files WHERE id IN (" + ",".join("?" * len(part)) + ")", part).fetchall()}
            for n in part:
                p = os.path.join(UPLOAD_DIR, n)
                try:
                    if n not in known and os.path.getmtime(p) < cutoff:
                        os.remove(p)
                        removed += 1
                except OSError:
                    pass
    return removed


async def cleanup_loop() -> None:
    while True:
        await asyncio.sleep(6 * 3600)
        try:
            if CLUSTER.leader:  # once for the whole cluster
                await asyncio.to_thread(purge_old)
                await asyncio.to_thread(cleanup_storage)
            if icqcluster.ENABLED:
                await asyncio.to_thread(sweep_local_files)
        except Exception as e:  # noqa: BLE001
            print("cleanup failed:", e)


async def _cluster_send(uins: List[int], payload: dict) -> None:
    text = json.dumps(payload, ensure_ascii=False)
    for u in uins:
        await manager.deliver(u, text)


async def _cluster_node_lost(uins: List[int]) -> None:
    # users that were connected only through a server that went down are offline now
    if not CLUSTER.leader:
        return
    for u in uins:
        if not manager.online(u):
            try:
                await broadcast_presence(u, False)
            except Exception:  # noqa: BLE001
                pass


@app.on_event("startup")
async def _start_cleanup() -> None:
    DELTAS.start()  # archive the served APK, build patches from older versions in the background
    CLUSTER.on_send = _cluster_send
    CLUSTER.on_kick = lambda u: manager.kick(u, everywhere=False)
    CLUSTER.on_kick_tokens = lambda u, t: manager.kick_tokens(u, t, everywhere=False)
    CLUSTER.on_node_lost = _cluster_node_lost
    await CLUSTER.start()
    if icqcluster.ENABLED:
        asyncio.create_task(apk_sync_loop())
    asyncio.create_task(cleanup_loop())
    asyncio.create_task(expire_loop())


@app.get("/internal/apkinfo")
def internal_apkinfo(request: Request) -> dict:
    _cluster_auth(request)
    return {"code": DELTAS.current_code(), "sha": DELTAS.sha()}


@app.get("/internal/apkfile")
def internal_apkfile(f: str, request: Request):
    _cluster_auth(request)
    p = APK_PATH if f == "apk" else os.path.join(os.path.dirname(APK_PATH), "version.json") if f == "json" else ""
    if not p or not os.path.exists(p):
        raise HTTPException(404, "Not Found")
    return FileResponse(p, media_type="application/octet-stream")


def sync_apk_from_peers() -> bool:
    """Cluster: the new APK is put on ONE server (install.sh) — the others take it from there by themselves."""
    import urllib.request
    mine = DELTAS.current_code()
    hdr = {"X-ICQ-Cluster": icqcluster.SECRET}
    for url in CLUSTER.peers():
        try:
            with urllib.request.urlopen(urllib.request.Request(url + "/internal/apkinfo", headers=hdr), timeout=10) as r:
                info = json.loads(r.read())
            if int(info.get("code") or 0) <= mine:
                continue
            base = os.path.dirname(APK_PATH)
            os.makedirs(base, exist_ok=True)
            tmp_apk, tmp_json = APK_PATH + ".sync", os.path.join(base, "version.json.sync")
            for f, dst in (("apk", tmp_apk), ("json", tmp_json)):
                req = urllib.request.Request(f"{url}/internal/apkfile?f={f}", headers=hdr)
                with urllib.request.urlopen(req, timeout=600) as r, open(dst, "wb") as out:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        out.write(chunk)
            if apkdelta.sha256_of(tmp_apk) != info.get("sha"):
                os.remove(tmp_apk)
                continue
            DELTAS.archive_current()  # the old one stays for small patches
            os.replace(tmp_apk, APK_PATH)
            os.replace(tmp_json, os.path.join(base, "version.json"))
            print(f"[cluster] APK {mine} -> {info.get('code')} taken from {url}", flush=True)
            DELTAS.start()
            return True
        except Exception as e:  # noqa: BLE001
            print(f"[cluster] apk sync from {url}: {e}", flush=True)
    return False


async def apk_sync_loop() -> None:
    await asyncio.sleep(20)
    while True:
        try:
            await asyncio.to_thread(sync_apk_from_peers)
        except Exception as e:  # noqa: BLE001
            print("[cluster] apk sync:", e, flush=True)
        await asyncio.sleep(120)


@app.on_event("shutdown")
async def _stop_cluster() -> None:
    await CLUSTER.stop()


# ------------------------------------------------------------------ moderation
def fmt_duration(seconds: int) -> str:
    if seconds <= 0:
        return "навсегда"
    if seconds % 86400 == 0:
        return f"на {seconds // 86400} дн."
    if seconds % 3600 == 0:
        return f"на {seconds // 3600} ч"
    if seconds % 60 == 0:
        return f"на {seconds // 60} мин"
    return f"на {seconds} с"


def parse_duration(s: str) -> Optional[int]:
    m = re.fullmatch(r"(\d{1,5})\s*([smhdwсмчдн]?)", s.strip().lower())
    if not m:
        return None
    n = int(m.group(1))
    unit = m.group(2) or "m"
    mult = {"s": 1, "с": 1, "m": 60, "м": 60, "h": 3600, "ч": 3600, "d": 86400, "д": 86400, "w": 604800, "н": 604800}
    return n * mult[unit]


def moderate(c: sqlite3.Connection, gid: int, actor: int, target: int, action: str, seconds: int) -> Optional[sqlite3.Row]:
    """Applies a moderation action. Returns the system message (or None); raises HTTPException with a reason."""
    g = require_group(c, gid, actor)
    a_role = role_of(c, gid, actor)
    if a_role not in ("owner", "admin"):
        raise HTTPException(403, "Это могут только администраторы")
    if target == actor and action not in ("unmute",):
        raise HTTPException(400, "Нельзя применить к себе")
    t_role = role_of(c, gid, target)
    tname = uname(c, target)
    me = uname(c, actor)
    if action == "unban":
        c.execute("DELETE FROM group_bans WHERE gid=? AND uin=?", (gid, target))
        return insert_system(c, gid, actor, f"{me} разблокировал(а) {tname}") if g["kind"] != "channel" else None
    if t_role is None:
        raise HTTPException(404, "Участник не найден")
    if t_role == "owner" or (t_role == "admin" and a_role != "owner"):
        raise HTTPException(403, "Нельзя применить к администратору")
    if action == "mute":
        until = -1 if seconds <= 0 else now_ms() + seconds * 1000
        c.execute("UPDATE group_members SET muted_until=? WHERE gid=? AND uin=?", (until, gid, target))
        return insert_bot(c, gid, f"🔇 {tname} не может писать {fmt_duration(seconds)} (решение: {me})")
    if action == "unmute":
        c.execute("UPDATE group_members SET muted_until=0 WHERE gid=? AND uin=?", (gid, target))
        return insert_bot(c, gid, f"🔊 {tname} снова может писать (решение: {me})")
    if action in ("admin", "unadmin"):
        if a_role != "owner":
            raise HTTPException(403, "Назначать администраторов может только создатель")
        c.execute(
            "UPDATE group_members SET role=?, muted_until=0 WHERE gid=? AND uin=?",
            ("admin" if action == "admin" else "member", gid, target),
        )
        verb = "назначил(а) администратором" if action == "admin" else "снял(а) права администратора с"
        return insert_bot(c, gid, ("⭐ " if action == "admin" else "") + f"{me} {verb} {tname}")
    if action in ("ban", "kick"):
        if action == "ban":
            c.execute("INSERT OR IGNORE INTO group_bans(gid, uin) VALUES(?,?)", (gid, target))
        return None  # removal (with its own system message) is done by the caller via leave_group
    raise HTTPException(400, "Неизвестное действие")


async def moderate_api(gid: int, actor: int, target: int, action: str, seconds: int) -> None:
    with conn() as c:
        row = moderate(c, gid, actor, target, action, seconds)
        is_channel = (c.execute("SELECT kind FROM groups WHERE id=?", (gid,)).fetchone() or {"kind": ""})["kind"] == "channel"
        verb = "заблокировал(а)" if action == "ban" else "удалил(а)"
        actor_name, target_name = uname(c, actor), uname(c, target)
    if action in ("ban", "kick"):
        with conn() as c:
            c.execute("DELETE FROM group_members WHERE gid=? AND uin=?", (gid, target))
            log_group(c, gid, target, action, actor)
            sys_row = None if is_channel else insert_bot(
                c, gid, ("⛔ " if action == "ban" else "👋 ") + f"{actor_name} {verb} {target_name}"
            )
        await manager.send(target, {"type": "group_gone", "id": gid})
        await push_group(gid, sys_rows=[sys_row] if sys_row is not None else [])
        return
    await push_group(gid, sys_rows=[row] if row is not None else [])


@app.post("/api/groups/{gid}/moderate")
async def moderate_endpoint(gid: int, body: ModerateIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    await moderate_api(gid, uin, body.uin, body.action, body.seconds)
    with conn() as c:
        g = c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone()
        return group_view(c, g, uin) if g else {"ok": True}


@app.get("/api/groups/{gid}/bans")
def group_bans(gid: int, authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        require_group_admin(c, gid, uin)
        rows = c.execute(
            "SELECT u.* FROM group_bans b JOIN users u ON u.uin=b.uin WHERE b.gid=?", (gid,)
        ).fetchall()
        return [user_json(r) for r in rows]


COMMANDS = {".mute", ".unmute", ".ban", ".unban", ".kick", ".admin", ".unadmin", ".del", ".help", ".мут", ".бан"}
HELP_TEXT = (
    "Команды администратора (ответьте на сообщение участника или укажите ID):\n"
    ".mute 5m / 1h / 1d — запретить писать на время\n"
    ".mute — запретить навсегда\n"
    ".unmute — снять запрет\n"
    ".ban — заблокировать и удалить из чата\n"
    ".unban ID — разблокировать\n"
    ".kick — удалить из чата\n"
    ".admin / .unadmin — назначить / снять админа (только создатель)\n"
    ".del — удалить сообщение"
)


async def handle_command(sender: int, ws: WebSocket, gid: int, text: str, reply_to: Optional[int], client_id: str) -> None:
    parts = text.split()
    cmd = parts[0].lower().replace(".мут", ".mute").replace(".бан", ".ban")
    args = parts[1:]

    async def done(note: Optional[str] = None) -> None:
        await ws.send_text(json.dumps({"type": "command_done", "client_id": client_id, "text": note}, ensure_ascii=False))

    if cmd == ".help":
        await done(None)
        with conn() as c:
            g = c.execute("SELECT name FROM groups WHERE id=?", (gid,)).fetchone()
        title = g["name"] if g else "чат"
        await bot_send(sender, f"🛡 Справка для «{title}»\n\n" + HELP_TEXT)
        return
    target = None
    with conn() as c:
        if reply_to is not None:
            r = c.execute("SELECT from_uin, group_id FROM messages WHERE id=?", (reply_to,)).fetchone()
            if r and r["group_id"] == gid:
                target = r["from_uin"]
        rest = []
        for a in args:
            d = a.lstrip("@")
            if target is None and d.isdigit() and len(d) >= 5:
                target = int(d)
            else:
                rest.append(a)
        if cmd == ".del":
            if not is_admin(c, gid, sender):
                await fail(ws, client_id, "Удалять чужие сообщения могут только администраторы")
                return
            if reply_to is None:
                await fail(ws, client_id, "Ответьте командой .del на сообщение")
                return
    if cmd == ".del":
        await handle_delete(sender, {"id": reply_to})
        await done()
        return
    if target is None:
        await fail(ws, client_id, "Ответьте командой на сообщение участника или укажите его ID")
        return
    seconds = 0
    if cmd == ".mute" and rest:
        p = parse_duration(rest[0])
        if p is None:
            await fail(ws, client_id, "Неверное время. Пример: .mute 5m, .mute 1h, .mute 1d")
            return
        seconds = p
    try:
        await moderate_api(gid, sender, target, cmd[1:], seconds)
    except HTTPException as e:
        await fail(ws, client_id, str(e.detail))
        return
    await done()


# --------------------------------------------------------------------- calls
CALL_TTL_MS = 45_000
pending_calls: Dict[int, list] = {}


@app.get("/api/ice")
def ice_servers(authorization: Optional[str] = Header(None)) -> list:
    require_uin(authorization)
    servers = [{"urls": ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"]}]
    turn = os.environ.get("ICQ_TURN_URL")
    if turn:
        servers.append(
            {
                "urls": [u.strip() for u in turn.split(",") if u.strip()],
                "username": os.environ.get("ICQ_TURN_USER", ""),
                "credential": os.environ.get("ICQ_TURN_PASS", ""),
            }
        )
    return servers


async def handle_call(sender: int, data: dict) -> None:
    to = _int(data.get("to"))
    payload = data.get("data")
    if to is None or to == sender or not isinstance(payload, dict):
        return
    with conn() as c:
        if to == BOT_UIN or is_blocked(c, to, sender) or not c.execute("SELECT 1 FROM users WHERE uin=? AND banned=0", (to,)).fetchone():
            await manager.send(sender, {"type": "call", "from": to, "data": {"kind": "busy", "call_id": payload.get("call_id")}})
            return
        caller = c.execute("SELECT * FROM users WHERE uin=?", (sender,)).fetchone()
    msg = {"type": "call", "from": sender, "data": payload}
    kind = payload.get("kind")
    if kind == "offer":
        msg["user"] = user_json(caller)
    if manager.connected(to):
        await manager.send(to, msg)
    else:
        if kind in ("offer", "ice"):
            if icqcluster.ENABLED:
                await CLUSTER.push_pending(to, [now_ms(), msg], CALL_TTL_MS // 1000 + 5)
            else:
                q = pending_calls.setdefault(to, [])
                q.append((now_ms(), msg))
        if kind == "offer":
            push_call(to, sender, caller["name"], bool(payload.get("video")))
    if kind in ("hangup", "decline") and icqcluster.ENABLED:
        await CLUSTER.drop_pending(to, payload.get("call_id"))
    if kind in ("hangup", "decline"):
        q = pending_calls.get(to)
        if q:
            cid = payload.get("call_id")
            pending_calls[to] = [x for x in q if x[1]["data"].get("call_id") != cid]


async def flush_calls(uin: int) -> None:
    q = pending_calls.pop(uin, None)
    if icqcluster.ENABLED:
        q = (q or []) + [tuple(x) for x in await CLUSTER.take_pending(uin)]
    if not q:
        return
    now = now_ms()
    for ts, msg in q:
        if now - ts < CALL_TTL_MS:
            await manager.send(uin, msg)


# ------------------------------------------------------------------------ admin
basic = HTTPBasic(auto_error=False)

# what an admin may do (the super admin may do everything, also manage admins)
ADMIN_PERMS = {
    "sms": "Коды и SMS",
    "users": "Пользователи",
    "verify": "Галочки",
    "groups": "Группы и каналы",
    "balance": "Баланс",
    "fragment": "Fragment",
}
# which permission an admin API path needs
_PATH_PERMS = [
    ("/admin/api/pending", "sms"), ("/admin/api/sent", "sms"), ("/admin/api/sms", "sms"),
    ("/admin/api/users", "users"), ("/admin/api/verify", "verify"), ("/admin/api/groups", "groups"),
    ("/admin/api/balance", "balance"), ("/admin/api/fragment", "fragment"), ("/admin/api/cluster", "super"),
    ("/admin/api/admins", "super"), ("/admin/api/log", "super"), ("/admin/api/servers", "super"),
]


class AdminActor:
    def __init__(self, aid: int, login: str, role: str, perms: List[str]):
        self.id, self.login, self.role, self.perms = aid, login, role, perms

    @property
    def is_super(self) -> bool:
        return self.role == "super"

    def can(self, perm: str) -> bool:
        return self.is_super or (perm != "super" and perm in self.perms)

    def json(self) -> dict:
        return {"id": self.id, "login": self.login, "role": self.role,
                "perms": list(ADMIN_PERMS) if self.is_super else [p for p in self.perms if p in ADMIN_PERMS]}


SUPER = AdminActor(0, "admin", "super", list(ADMIN_PERMS))


def _admin_row_actor(r) -> AdminActor:
    try:
        perms = json.loads(r["perms"] or "[]")
    except ValueError:
        perms = []
    return AdminActor(r["id"], r["login"], r["role"], [p for p in perms if p in ADMIN_PERMS])


def _check_admin_login(login: str, password: str) -> Optional[AdminActor]:
    login = (login or "").strip()
    if login.lower() == "admin" and secrets.compare_digest(password or "", ADMIN_PASSWORD):
        return SUPER
    with conn() as c:
        r = c.execute("SELECT * FROM admins WHERE lower(login)=lower(?) AND enabled=1", (login,)).fetchone()
    if r and check_password(password or "", r["pw_hash"]):
        return _admin_row_actor(r)
    return None


def admin_actor(request: Request, creds: Optional[HTTPBasicCredentials] = Depends(basic),
                authorization: Optional[str] = Header(None)) -> AdminActor:
    """Web panel: Basic login/password. Admin app: Bearer token from /admin/api/login."""
    actor = None
    if authorization and authorization.lower().startswith("bearer "):
        tok = authorization[7:].strip()
        with conn() as c:
            t = c.execute("SELECT * FROM admin_tokens WHERE token=?", (tok,)).fetchone()
            if t:
                if t["admin_id"] == 0:
                    actor = SUPER
                else:
                    r = c.execute("SELECT * FROM admins WHERE id=? AND enabled=1", (t["admin_id"],)).fetchone()
                    actor = _admin_row_actor(r) if r else None
                if actor and now_ms() - (t["last_used"] or 0) > 60_000:
                    c.execute("UPDATE admin_tokens SET last_used=? WHERE token=?", (now_ms(), tok))
                    if actor.id:
                        c.execute("UPDATE admins SET last_seen=? WHERE id=?", (now_ms(), actor.id))
        if actor is None:
            raise HTTPException(401, "Войдите снова")
    elif creds is not None:
        actor = _check_admin_login(creds.username, creds.password)
    if actor is None:
        raise HTTPException(401, "Unauthorized", headers={"WWW-Authenticate": "Basic"})
    path = request.url.path
    for prefix, perm in _PATH_PERMS:
        if path.startswith(prefix):
            if not actor.can(perm):
                raise HTTPException(403, "Нет доступа к этому разделу")
            break
    return actor


def require_admin(actor: AdminActor = Depends(admin_actor)) -> AdminActor:
    return actor


def admin_log(actor: AdminActor, action: str) -> None:
    with conn() as c:
        c.execute("INSERT INTO admin_log(admin_id, login, action, ts) VALUES(?,?,?,?)",
                  (actor.id, actor.login, action[:300], now_ms()))


_admin_fails: Dict[str, list] = {}


@app.post("/admin/api/login")
def admin_login(body: dict, request: Request) -> dict:
    """Admin app: login + password -> token."""
    login = str(body.get("login") or "").strip()
    key = login.lower() + "|" + client_ip(request)
    now = now_ms()
    fails = [t for t in _admin_fails.get(key, []) if now - t < 15 * 60_000]
    if len(fails) >= 8:
        raise HTTPException(429, "Слишком много попыток. Подождите 15 минут")
    actor = _check_admin_login(login, str(body.get("password") or ""))
    if actor is None:
        fails.append(now)
        _admin_fails[key] = fails
        raise HTTPException(400, "Неверный логин или пароль")
    _admin_fails.pop(key, None)
    tok = secrets.token_hex(32)
    device = str(body.get("device") or "")[:80]
    with conn() as c:
        c.execute("INSERT INTO admin_tokens(token, admin_id, created, last_used, device) VALUES(?,?,?,?,?)",
                  (tok, actor.id, now, now, device))
        if actor.id:
            c.execute("UPDATE admins SET last_seen=?, device=? WHERE id=?", (now, device, actor.id))
    admin_log(actor, f"вход в приложение ({device or 'устройство'})")
    return {"token": tok, "admin": actor.json(), "perm_names": ADMIN_PERMS}


@app.post("/admin/api/logout")
def admin_logout(authorization: Optional[str] = Header(None), _: AdminActor = Depends(require_admin)) -> dict:
    if authorization and authorization.lower().startswith("bearer "):
        with conn() as c:
            c.execute("DELETE FROM admin_tokens WHERE token=?", (authorization[7:].strip(),))
    return {"ok": True}


@app.get("/admin/api/me")
def admin_me(actor: AdminActor = Depends(require_admin)) -> dict:
    now = now_ms()
    with conn() as c:
        users = c.execute("SELECT COUNT(*) AS n FROM users WHERE uin NOT IN (?, ?)", (BOT_UIN, SERVICE_UIN)).fetchone()["n"]
        waiting = c.execute("SELECT COUNT(*) AS n FROM otp WHERE sent=0 AND created>? AND phone NOT LIKE 'mail:%'",
                            (now - CODE_TTL_MS,)).fetchone()["n"]
        auto = c.execute("SELECT login, device FROM admins WHERE auto_sms=1 AND enabled=1 AND last_seen>?",
                         (now - 120_000,)).fetchall()
    auto_list = [{"login": r["login"], "device": r["device"] or ""} for r in auto]
    if _super_auto_seen and now - _super_auto_seen[0] < 120_000:
        auto_list.append({"login": "admin", "device": _super_auto_seen[1]})
    return {"admin": actor.json(), "perm_names": ADMIN_PERMS, "users": users, "waiting_sms": waiting,
            "online": manager.online_count(), "auto_sms": auto_list}


# ---------------------------------------------------------------- admins (super admin only)
class AdminIn(BaseModel):
    login: Optional[str] = None
    password: Optional[str] = None
    role: Optional[str] = None
    perms: Optional[List[str]] = None
    enabled: Optional[bool] = None


def _admin_json(r) -> dict:
    a = _admin_row_actor(r).json()
    a.update({"enabled": bool(r["enabled"]), "created": r["created"], "last_seen": r["last_seen"],
              "device": r["device"] or "", "auto_sms": bool(r["auto_sms"]), "sms_sent": r["sms_sent"]})
    return a


@app.get("/admin/api/admins")
def admins_list(_: AdminActor = Depends(require_admin)) -> list:
    with conn() as c:
        return [_admin_json(r) for r in c.execute("SELECT * FROM admins ORDER BY id").fetchall()]


@app.post("/admin/api/admins")
def admins_create(body: AdminIn, actor: AdminActor = Depends(require_admin)) -> dict:
    login = (body.login or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_.\-]{3,32}", login) or login.lower() == "admin":
        raise HTTPException(400, "Логин: 3–32 символа (латиница, цифры, _ . -)")
    if len(body.password or "") < 6:
        raise HTTPException(400, "Пароль — от 6 символов")
    role = "super" if body.role == "super" else "admin"
    perms = [p for p in (body.perms or ["sms"]) if p in ADMIN_PERMS]
    with conn() as c:
        if c.execute("SELECT 1 FROM admins WHERE lower(login)=lower(?)", (login,)).fetchone():
            raise HTTPException(409, "Такой логин уже есть")
        c.execute("INSERT INTO admins(login, pw_hash, role, perms, created) VALUES(?,?,?,?,?)",
                  (login, hash_password(body.password), role, json.dumps(perms), now_ms()))
        r = c.execute("SELECT * FROM admins WHERE lower(login)=lower(?)", (login,)).fetchone()
    admin_log(actor, f"создал админа {login} ({role}: {', '.join(perms)})")
    return _admin_json(r)


@app.patch("/admin/api/admins/{aid}")
def admins_edit(aid: int, body: AdminIn, actor: AdminActor = Depends(require_admin)) -> dict:
    with conn() as c:
        r = c.execute("SELECT * FROM admins WHERE id=?", (aid,)).fetchone()
        if not r:
            raise HTTPException(404, "Админ не найден")
        if body.password:
            if len(body.password) < 6:
                raise HTTPException(400, "Пароль — от 6 символов")
            c.execute("UPDATE admins SET pw_hash=? WHERE id=?", (hash_password(body.password), aid))
            c.execute("DELETE FROM admin_tokens WHERE admin_id=?", (aid,))
        if body.role in ("super", "admin"):
            c.execute("UPDATE admins SET role=? WHERE id=?", (body.role, aid))
        if body.perms is not None:
            c.execute("UPDATE admins SET perms=? WHERE id=?", (json.dumps([p for p in body.perms if p in ADMIN_PERMS]), aid))
        if body.enabled is not None:
            c.execute("UPDATE admins SET enabled=? WHERE id=?", (int(body.enabled), aid))
            if not body.enabled:
                c.execute("DELETE FROM admin_tokens WHERE admin_id=?", (aid,))
        r = c.execute("SELECT * FROM admins WHERE id=?", (aid,)).fetchone()
    admin_log(actor, f"изменил админа {r['login']}")
    return _admin_json(r)


@app.delete("/admin/api/admins/{aid}")
def admins_delete(aid: int, actor: AdminActor = Depends(require_admin)) -> dict:
    with conn() as c:
        r = c.execute("SELECT login FROM admins WHERE id=?", (aid,)).fetchone()
        c.execute("DELETE FROM admins WHERE id=?", (aid,))
        c.execute("DELETE FROM admin_tokens WHERE admin_id=?", (aid,))
        c.execute("UPDATE otp SET claimed_by=NULL WHERE claimed_by=? AND sent=0", (aid,))
    if r:
        admin_log(actor, f"удалил админа {r['login']}")
    return {"ok": True}


@app.get("/admin/api/log")
def admins_log(_: AdminActor = Depends(require_admin)) -> list:
    with conn() as c:
        rows = c.execute("SELECT * FROM admin_log ORDER BY id DESC LIMIT 300").fetchall()
    return [{"login": r["login"], "action": r["action"], "ts": r["ts"]} for r in rows]


# ---------------------------------------------------------------- SMS with the code: by hand or automatically
SMS_CLAIM_MS = 45_000  # a phone that took a code but did not report back: the code goes to another phone
_super_auto_seen: list = []


def sms_text(code: str) -> str:
    return f"ICQ New: {code} — код для входа. Никому не сообщайте его."


def _claim_next(actor: AdminActor) -> Optional[dict]:
    now = now_ms()
    with conn() as c:
        rows = c.execute(
            "SELECT phone, code, created FROM otp WHERE sent=0 AND created>? AND phone NOT LIKE 'mail:%'"
            " AND (claimed_by IS NULL OR claimed_at<?) ORDER BY created LIMIT 5",
            (now - CODE_TTL_MS + 60_000, now - SMS_CLAIM_MS),
        ).fetchall()
        for r in rows:
            cur = c.execute(
                "UPDATE otp SET claimed_by=?, claimed_at=? WHERE phone=? AND code=? AND sent=0"
                " AND (claimed_by IS NULL OR claimed_at<?)",
                (actor.id, now, r["phone"], r["code"], now - SMS_CLAIM_MS),
            )
            if cur.rowcount == 1:
                return {"phone": "+" + r["phone"], "code": r["code"], "text": sms_text(r["code"]),
                        "age": (now - r["created"]) // 1000}
    return None


@app.get("/admin/api/sms/next")
async def sms_next(wait: int = 25, device: str = "", actor: AdminActor = Depends(require_admin)):
    """Auto-SMS phone: waits up to [wait] s for the next code to send; this phone then sends it from its SIM."""
    with conn() as c:
        if actor.id:
            c.execute("UPDATE admins SET auto_sms=1, last_seen=?, device=COALESCE(NULLIF(?, ''), device) WHERE id=?",
                      (now_ms(), device[:80], actor.id))
    if not actor.id:
        _super_auto_seen[:] = [now_ms(), device[:80]]
    deadline = time.time() + max(0, min(wait, 50))
    while True:
        item = await asyncio.to_thread(_claim_next, actor)
        if item or time.time() >= deadline:
            return item or Response(status_code=204)
        await asyncio.sleep(1)


class SmsResultIn(BaseModel):
    phone: str
    code: str
    ok: bool
    error: Optional[str] = None


@app.post("/admin/api/sms/result")
def sms_result(body: SmsResultIn, actor: AdminActor = Depends(require_admin)) -> dict:
    phone = digits(body.phone)
    with conn() as c:
        if body.ok:
            c.execute("UPDATE otp SET sent=1, sent_by=?, sent_at=?, sms_error=NULL WHERE phone=? AND code=?",
                      (actor.id, now_ms(), phone, body.code))
            if actor.id:
                c.execute("UPDATE admins SET sms_sent=sms_sent+1 WHERE id=?", (actor.id,))
        else:
            c.execute("UPDATE otp SET claimed_by=NULL, claimed_at=0, sms_error=? WHERE phone=? AND code=?",
                      ((body.error or "ошибка")[:200], phone, body.code))
    return {"ok": True}


@app.post("/admin/api/sms/auto")
def sms_auto(body: dict, actor: AdminActor = Depends(require_admin)) -> dict:
    """The phone switched automatic SMS on / off."""
    on = bool(body.get("on"))
    with conn() as c:
        if actor.id:
            c.execute("UPDATE admins SET auto_sms=? WHERE id=?", (int(on), actor.id))
            if not on:
                c.execute("UPDATE otp SET claimed_by=NULL WHERE claimed_by=? AND sent=0", (actor.id,))
    if not actor.id and not on:
        _super_auto_seen.clear()
    admin_log(actor, "авто-SMS " + ("включил" if on else "выключил"))
    return {"ok": True}


def _local_extra_stats() -> dict:
    """Besides CPU / RAM / disk: connections and the size of the stored files on this server."""
    def du(path: str) -> int:
        total = 0
        try:
            for e in os.scandir(path):
                if e.is_file(follow_symlinks=False):
                    total += e.stat().st_size
        except OSError:
            pass
        return total
    st = {"connections": sum(len(v) for v in manager.conns.values()), "users_here": len(manager.conns)}
    now = time.time()
    if not _du_cache or now - _du_cache[0] > 600:
        _du_cache[:] = [now, du(UPLOAD_DIR), du(AVATAR_DIR)]
    st["files_bytes"], st["avatars_bytes"] = _du_cache[1], _du_cache[2]
    return st


_du_cache: list = []
CLUSTER.extra_stats = _local_extra_stats


@app.get("/admin/api/cluster")
async def admin_cluster(_: None = Depends(require_admin)) -> dict:
    """Super admin: every server (VPS) — alive or not, leader, online people, CPU, RAM, disk, network."""
    info = CLUSTER.info()
    per: Dict[str, int] = {icqcluster.NODE_ID: len(manager.conns)}
    for u, d in CLUSTER.remote.items():
        for n in d:
            per[n] = per.get(n, 0) + 1
    stats = await CLUSTER.all_stats()
    mine = await asyncio.to_thread(icqcluster.system_stats)
    mine.update(_local_extra_stats())
    stats[icqcluster.NODE_ID] = mine
    nodes = []
    for n in sorted(set(info["nodes"]) | {icqcluster.NODE_ID}):
        nodes.append({"id": n, "url": info["nodes"].get(n, icqcluster.NODE_URL), "alive": n in CLUSTER.alive,
                      "me": n == icqcluster.NODE_ID, "online": per.get(n, 0), "stats": stats.get(n, {})})
    db_size = 0
    try:
        with conn() as c:
            if icqdb.IS_PG:
                db_size = c.execute("SELECT pg_database_size(current_database()) AS n").fetchone()["n"]
            elif os.path.exists(DB_PATH):
                db_size = os.path.getsize(DB_PATH)
    except Exception:  # noqa: BLE001
        pass
    return {"enabled": info["enabled"], "leader": icqcluster.NODE_ID if CLUSTER.leader else None,
            "db": "postgresql" if icqdb.IS_PG else "sqlite", "db_size": db_size, "nodes": nodes,
            "can_add": is_main_server() or (icqcluster.ENABLED and "main" in CLUSTER.alive),
            "online": manager.online_count()}


# ---------------------------------------------------------------- add / remove a VPS from the admin panel
CODE_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.environ.get("ICQ_HOME") or CODE_DIR   # where cluster.token / cluster.allow live
IP_RE = re.compile(r"^(?:\d{1,3}\.){3}\d{1,3}$")


def is_main_server() -> bool:
    """The main server keeps PostgreSQL + Redis and the join code (cluster-main.sh made it)."""
    return os.path.exists(os.path.join(SERVER_DIR, "cluster.token"))


def _job_log(jid: int, line: str = "", status: Optional[str] = None) -> None:
    with conn() as c:
        if line:
            c.execute("UPDATE server_jobs SET log=substr(log || ?, -60000), updated=? WHERE id=?",
                      (line if line.endswith("\n") else line + "\n", now_ms(), jid))
        if status:
            c.execute("UPDATE server_jobs SET status=?, updated=? WHERE id=?", (status, now_ms(), jid))


def _bundle() -> bytes:
    """Everything a new server needs (code, site, scripts, the current APK) as one .tar.gz."""
    import io
    import tarfile
    buf = io.BytesIO()
    names = ["server.py", "apkdelta.py", "db.py", "cluster.py", "migrate_to_pg.py", "requirements.txt", "ssl.sh",
             "acme-hook.sh", "acme-clean.sh", "cluster-fw.sh", "install.sh", "cluster-join.sh", "cluster-add.sh",
             "cluster-remove.sh", "cluster-main.sh", "CLUSTER.md", "firebase.json"]
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for n in names:
            p = os.path.join(CODE_DIR, n)
            if os.path.exists(p):
                tar.add(p, arcname="server/" + n)
        for d in ("web",):
            p = os.path.join(CODE_DIR, d)
            if os.path.isdir(p):
                tar.add(p, arcname="server/" + d)
        apk_dir = os.path.dirname(APK_PATH)
        for n in ("icq-new.apk", "version.json"):
            p = os.path.join(apk_dir, n)
            if os.path.exists(p):
                tar.add(p, arcname="server/apk/" + n)
    return buf.getvalue()


def _allow_ip(ip: str, add: bool) -> None:
    allow = os.path.join(SERVER_DIR, "cluster.allow")
    try:
        with open(allow) as f:
            ips = {x.strip() for x in f if x.strip()}
    except OSError:
        ips = set()
    if add:
        ips.add(ip)
    else:
        ips.discard(ip)
    with open(allow, "w") as f:
        f.write("\n".join(sorted(ips)) + "\n")
    fw = os.path.join(SERVER_DIR, "cluster-fw.sh")
    if os.path.exists(fw) and os.path.abspath(allow) == "/opt/icq-server/cluster.allow":
        import subprocess
        subprocess.run(["bash", fw], capture_output=True, timeout=60)


def _install_server(jid: int, ip: str, port: int, user: str, password: str) -> None:
    """Runs on the main server: SSH to the new VPS, copy ICQ New there, join it to the cluster."""
    import paramiko
    try:
        _job_log(jid, f"==> {ip}: rugsat berilýär (baza we Redis şu serwere açylýar)", "running")
        _allow_ip(ip, True)
        with open(os.path.join(SERVER_DIR, "cluster.token")) as f:
            token = f.read().strip()
        _job_log(jid, f"==> SSH: {user}@{ip}:{port}")
        cli = paramiko.SSHClient()
        cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        cli.connect(ip, port=port, username=user, password=password, timeout=20, banner_timeout=30,
                    auth_timeout=30, look_for_keys=False, allow_agent=False)
        _job_log(jid, "   birikdi ✔")
        data = _bundle()
        _job_log(jid, f"==> Faýllar göçürilýär ({len(data) // 1024 // 1024} MB)")
        sftp = cli.open_sftp()
        with sftp.open("/root/icq-server.tar.gz", "wb") as f:
            f.set_pipelined(True)
            f.write(data)
        sftp.close()
        sudo = "" if user == "root" else "sudo -S -p '' "
        cmd = (f"cd /root && rm -rf /root/server && tar xzf icq-server.tar.gz && cd /root/server && "
               f"{sudo}bash cluster-join.sh {token} 2>&1")
        _job_log(jid, "==> Gurulýar (3–6 minut): cluster-join.sh")
        chan = cli.get_transport().open_session()
        chan.get_pty()
        chan.exec_command(cmd)
        if sudo:
            chan.sendall((password + "\n").encode())
        buf = b""
        while True:
            if chan.recv_ready():
                buf += chan.recv(65536)
                *lines, buf = buf.split(b"\n")
                for ln in lines:
                    t = ln.decode("utf-8", "replace").rstrip("\r")
                    if t.strip():
                        _job_log(jid, t)
            elif chan.exit_status_ready():
                break
            else:
                time.sleep(0.3)
        rc = chan.recv_exit_status()
        if buf.strip():
            _job_log(jid, buf.decode("utf-8", "replace"))
        cli.close()
        if rc == 0:
            _job_log(jid, f"✔ {ip} goşuldy. Soňky ädim: DNS-e A ýazgy goş — icq-new.online we www → {ip}", "done")
        else:
            _job_log(jid, f"❌ cluster-join.sh ýalňyşlyk bilen gutardy (kod {rc})", "failed")
    except Exception as e:  # noqa: BLE001
        _job_log(jid, f"❌ {type(e).__name__}: {e}", "failed")


class AddServerIn(BaseModel):
    ip: str
    password: str
    user: str = "root"
    port: int = 22


@app.post("/admin/api/servers")
def admin_add_server(body: AddServerIn, request: Request, actor: AdminActor = Depends(require_admin)) -> dict:
    """Super admin: a new VPS by IP + SSH password; the main server installs and joins it by itself."""
    if not is_main_server():
        main = next((u for n, u in CLUSTER.nodes.items() if n == "main" and n in CLUSTER.alive), None)
        if main and icqcluster.ENABLED:
            import urllib.request
            req = urllib.request.Request(main + "/internal/servers", data=body.model_dump_json().encode(), method="POST",
                                         headers={"X-ICQ-Cluster": icqcluster.SECRET, "Content-Type": "application/json",
                                                  "X-ICQ-Admin": actor.login})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read())
        raise HTTPException(400, "Сначала один раз на главном сервере: bash cluster-main.sh (PostgreSQL + Redis)")
    return _start_add_server(body, actor.login)


def _start_add_server(body: AddServerIn, who: str) -> dict:
    ip = body.ip.strip()
    if not IP_RE.match(ip):
        raise HTTPException(400, "Введите IP-адрес, например 203.0.113.7")
    if not body.password:
        raise HTTPException(400, "Введите пароль SSH")
    with conn() as c:
        busy = c.execute("SELECT id FROM server_jobs WHERE ip=? AND status IN ('queued','running')", (ip,)).fetchone()
        if busy:
            return {"id": busy["id"], "already": True}
        cur = c.execute("INSERT INTO server_jobs(ip, status, log, created, updated) VALUES(?,?,?,?,?)",
                        (ip, "queued", f"{who}: täze serwer {ip}\n", now_ms(), now_ms()))
        jid = cur.lastrowid
        c.execute("INSERT INTO admin_log(admin_id, login, action, ts) VALUES(0,?,?,?)", (who, f"добавляет сервер {ip}", now_ms()))
    threading.Thread(target=_install_server, args=(jid, ip, max(1, min(body.port, 65535)), body.user.strip() or "root",
                                                   body.password), daemon=True).start()
    return {"id": jid}


@app.post("/internal/servers")
def internal_add_server(body: AddServerIn, request: Request) -> dict:
    _cluster_auth(request)
    if not is_main_server():
        raise HTTPException(400, "not main")
    return _start_add_server(body, request.headers.get("x-icq-admin", "admin"))


@app.delete("/internal/servers/allow")
def internal_disallow(ip: str, request: Request) -> dict:
    _cluster_auth(request)
    if is_main_server() and IP_RE.match(ip):
        _allow_ip(ip, False)
    return {"ok": True}


@app.get("/admin/api/servers/jobs")
def admin_server_jobs(_: AdminActor = Depends(require_admin)) -> list:
    with conn() as c:
        rows = c.execute("SELECT * FROM server_jobs ORDER BY id DESC LIMIT 20").fetchall()
    return [{"id": r["id"], "ip": r["ip"], "status": r["status"], "log": r["log"][-20000:], "created": r["created"],
             "updated": r["updated"]} for r in rows]


@app.delete("/admin/api/servers/{node}")
def admin_remove_server(node: str, actor: AdminActor = Depends(require_admin)) -> dict:
    """Takes a server out of the cluster: no more access to the database, gone from the list."""
    if node == "main":
        raise HTTPException(400, "Главный сервер убрать нельзя")
    url = CLUSTER.nodes.get(node, "")
    ip = re.sub(r"^https?://", "", url).split(":")[0] if url else ""
    if ip and is_main_server():
        _allow_ip(ip, False)
    elif ip and icqcluster.ENABLED and "main" in CLUSTER.alive:
        import urllib.request
        req = urllib.request.Request(CLUSTER.nodes["main"] + "/internal/servers/allow?ip=" + ip, method="DELETE",
                                     headers={"X-ICQ-Cluster": icqcluster.SECRET})
        try:
            urllib.request.urlopen(req, timeout=30).read()
        except Exception as e:  # noqa: BLE001
            print("[servers] remove on main failed:", e)
    if icqcluster.ENABLED and CLUSTER.rs is not None:
        CLUSTER.rs.hdel("icq:nodes", node)
        CLUSTER.rs.hdel("icq:stats", node)
    admin_log(actor, f"убрал сервер {node} {ip}")
    return {"ok": True, "ip": ip, "dns": f"Удалите A-запись {ip} у домена" if ip else ""}


@app.post("/admin/api/balance")
async def admin_balance(body: dict, actor: AdminActor = Depends(require_admin)) -> dict:
    """Top up (or take back) a user's balance: {who: ID / phone / @username, amount, reason}."""
    admin_log(actor, f"баланс {body.get('who')}: {body.get('amount')} Gold")
    who = str(body.get("who") or "").strip()
    try:
        amount = int(body.get("amount") or 0)
    except (TypeError, ValueError):
        amount = 0
    if not who or amount == 0:
        raise HTTPException(400, "Укажите пользователя и сумму")
    with conn() as c:
        u = None
        if who.startswith("@"):
            u = c.execute("SELECT * FROM users WHERE username=? COLLATE NOCASE", (who[1:],)).fetchone()
        elif who.isdigit() and len(who) <= 10:
            u = c.execute("SELECT * FROM users WHERE uin=?", (int(who),)).fetchone()
        if u is None:
            u = c.execute("SELECT * FROM users WHERE phone=?", (norm_phone(who),)).fetchone()
        if u is None:
            raise HTTPException(404, "Пользователь не найден")
        new = (u["balance"] or 0) + amount
        if new < 0:
            raise HTTPException(400, "Баланс не может быть меньше нуля")
        c.execute("UPDATE users SET balance=? WHERE uin=?", (new, u["uin"]))
        c.execute("INSERT INTO balance_log(uin, delta, reason, ts) VALUES(?,?,?,?)",
                  (u["uin"], amount, (body.get("reason") or "Пополнение (администратор)")[:200], now_ms()))
        uin, name = u["uin"], u["name"]
    if amount > 0:
        asyncio.create_task(service_send(uin, f"💰 Ваш баланс пополнен на **{amount} Gold**. Сейчас: {new} Gold."))
    return {"uin": uin, "name": name, "balance": new}


@app.get("/admin/api/balance")
def admin_balance_log(_: None = Depends(require_admin)) -> list:
    with conn() as c:
        rows = c.execute(
            "SELECT b.*, u.name FROM balance_log b LEFT JOIN users u ON u.uin=b.uin ORDER BY b.id DESC LIMIT 100"
        ).fetchall()
        return [{"uin": r["uin"], "name": r["name"], "delta": r["delta"], "reason": r["reason"], "ts": r["ts"]} for r in rows]


@app.post("/admin/api/fragment")
def admin_fragment_add(body: dict, _: None = Depends(require_admin)) -> dict:
    """Put an @name on sale: {username, price}. price <= 0 removes an unsold lot."""
    name = str(body.get("username") or "").strip().lstrip("@")
    try:
        price = int(body.get("price") or 0)
    except (TypeError, ValueError):
        price = 0
    if not LOT_RE.match(name):
        raise HTTPException(400, "Имя: латиница, цифры и _, до 32 символов")
    with conn() as c:
        if price <= 0:
            c.execute("DELETE FROM fragment_lots WHERE username=? AND sold_to IS NULL", (name,))
            return {"ok": True, "removed": name}
        taken = (c.execute("SELECT 1 FROM users WHERE lower(username)=lower(?)", (name,)).fetchone()
                 or c.execute("SELECT 1 FROM groups WHERE lower(username)=lower(?)", (name,)).fetchone()
                 or c.execute("SELECT 1 FROM user_usernames WHERE username=?", (name,)).fetchone())
        if taken:
            raise HTTPException(409, "Это имя уже занято")
        c.execute("INSERT OR REPLACE INTO fragment_lots(username, price, ts) VALUES(?,?,?)", (name, price, now_ms()))
    return {"ok": True, "username": name, "price": price}


@app.get("/admin/api/fragment")
def admin_fragment_list(_: None = Depends(require_admin)) -> list:
    with conn() as c:
        rows = c.execute(
            "SELECT f.*, u.name FROM fragment_lots f LEFT JOIN users u ON u.uin=f.sold_to ORDER BY f.sold_to IS NOT NULL, f.ts DESC LIMIT 300"
        ).fetchall()
        return [{"username": r["username"], "price": r["price"], "sold_to": r["sold_to"], "buyer": r["name"],
                 "sold_ts": r["sold_ts"]} for r in rows]


@app.get("/admin/api/pending")
def admin_pending(_: None = Depends(require_admin)) -> dict:
    now = now_ms()
    with conn() as c:
        rows = c.execute(
            "SELECT o.phone, o.code, o.created, o.attempts, o.sent, o.sent_at, o.sms_error, o.claimed_by, o.claimed_at,"
            " COALESCE(a.login, CASE WHEN o.sent_by=0 THEN 'admin' END) AS by_login"
            " FROM otp o LEFT JOIN admins a ON a.id=o.sent_by ORDER BY o.created DESC LIMIT 100"
        ).fetchall()
        users = c.execute("SELECT COUNT(*) AS n FROM users WHERE uin NOT IN (?, ?)", (BOT_UIN, SERVICE_UIN)).fetchone()["n"]
        msgs = c.execute("SELECT COUNT(*) AS n FROM messages WHERE kind<>'system'").fetchone()["n"]
        groups = c.execute("SELECT COUNT(*) AS n FROM groups WHERE kind<>'channel'").fetchone()["n"]
        channels = c.execute("SELECT COUNT(*) AS n FROM groups WHERE kind='channel'").fetchone()["n"]
    items = [
        {
            "phone": r["phone"],
            "code": r["code"],
            "age": (now - r["created"]) // 1000,
            "expired": now - r["created"] > CODE_TTL_MS,
            "sent": bool(r["sent"]),
            "email": r["phone"].startswith("mail:"),
            "by": r["by_login"] or "",
            "error": r["sms_error"] or "",
            "sending": not r["sent"] and r["claimed_by"] is not None and now - (r["claimed_at"] or 0) < SMS_CLAIM_MS,
        }
        for r in rows
    ]
    return {"items": items, "users": users, "messages": msgs, "groups": groups, "channels": channels, "online": manager.online_count()}


@app.post("/admin/api/sent/{phone}")
def admin_mark_sent(phone: str, actor: AdminActor = Depends(require_admin)) -> dict:
    with conn() as c:
        c.execute("UPDATE otp SET sent=1, sent_by=?, sent_at=? WHERE phone=?", (actor.id, now_ms(), digits(phone)))
    return {"ok": True}


@app.get("/admin/api/users")
def admin_users(q: str = "", _: None = Depends(require_admin)) -> list:
    q = q.strip()
    with conn() as c:
        if q:
            like = "%" + q.replace("%", "") + "%"
            rows = c.execute(
                "SELECT * FROM users WHERE name LIKE ? OR phone LIKE ? OR CAST(uin AS TEXT) LIKE ?"
                " ORDER BY uin DESC LIMIT 300",
                (like, like, like),
            ).fetchall()
        else:
            rows = c.execute("SELECT * FROM users ORDER BY uin DESC LIMIT 300").fetchall()
        out = []
        for r in rows:
            n = c.execute(
                "SELECT COUNT(*) AS n FROM messages WHERE from_uin=? AND kind<>'system'", (r["uin"],)
            ).fetchone()["n"]
            out.append(
                {
                    "uin": r["uin"],
                    "name": r["name"],
                    "phone": r["phone"],
                    "avatar_url": r["avatar_url"],
                    "online": manager.online(r["uin"]),
                    "last_seen": r["last_seen"],
                    "created": r["created"],
                    "banned": bool(r["banned"]),
                    "verified": bool(r["verified"]),
                    "username": r["username"],
                    "messages": n,
                }
            )
    return out


@app.post("/admin/api/users/{uin}/ban")
async def admin_ban(uin: int, ban: bool = True, actor: AdminActor = Depends(require_admin)) -> dict:
    admin_log(actor, ("заблокировал " if ban else "разблокировал ") + str(uin))
    with conn() as c:
        c.execute("UPDATE users SET banned=? WHERE uin=?", (int(ban), uin))
        if ban:
            c.execute("DELETE FROM tokens WHERE uin=?", (uin,))
    if ban:
        await manager.kick(uin)
    return {"ok": True}


@app.post("/admin/api/verify")
async def admin_verify(body: AdminVerifyIn, actor: AdminActor = Depends(require_admin)) -> dict:
    admin_log(actor, ("дал галочку " if body.on else "снял галочку ") + f"{body.kind} {body.id}")
    with conn() as c:
        if body.kind == "group":
            g = c.execute("SELECT id, name FROM groups WHERE id=?", (body.id,)).fetchone()
            if not g:
                raise HTTPException(404, "Группа/канал с таким ID не найден")
            c.execute("UPDATE groups SET verified=? WHERE id=?", (int(body.on), body.id))
            name = g["name"]
        else:
            u = c.execute("SELECT uin, name FROM users WHERE uin=?", (body.id,)).fetchone()
            if not u:
                raise HTTPException(404, "Пользователь с таким ID не найден")
            c.execute("UPDATE users SET verified=? WHERE uin=?", (int(body.on), body.id))
            name = u["name"]
    if body.kind == "group":
        await push_group(body.id)
    else:
        await broadcast_profile(body.id)
    return {"ok": True, "name": name, "on": body.on}


@app.get("/admin/api/groups")
def admin_groups(q: str = "", _: None = Depends(require_admin)) -> list:
    like = "%" + q.strip().replace("%", "") + "%"
    with conn() as c:
        rows = c.execute(
            "SELECT g.*, (SELECT COUNT(*) FROM group_members m WHERE m.gid=g.id) AS n FROM groups g"
            " WHERE g.name LIKE ? OR CAST(g.id AS TEXT) LIKE ? OR IFNULL(g.username,'') LIKE ?"
            " ORDER BY g.id DESC LIMIT 300",
            (like, like, like),
        ).fetchall()
        return [
            {"id": r["id"], "name": r["name"], "kind": r["kind"], "count": r["n"], "public": bool(r["public"]),
             "username": r["username"], "verified": bool(r["verified"]), "owner": r["owner"]}
            for r in rows
        ]


@app.delete("/admin/api/groups/{gid}")
async def admin_delete_group(gid: int, actor: AdminActor = Depends(require_admin)) -> dict:
    name = await delete_group_everywhere(gid)
    if name is None:
        raise HTTPException(404, "Группа/канал не найдены")
    admin_log(actor, f"удалил группу/канал {name} ({gid})")
    return {"ok": True}


@app.delete("/admin/api/users/{uin}")
async def admin_delete(uin: int, actor: AdminActor = Depends(require_admin)) -> dict:
    admin_log(actor, f"удалил аккаунт {uin}")
    await wipe_user(uin)
    return {"ok": True}


LOGO_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><circle cx="54" cy="54" r="54" fill="#000"/>
<g fill="#22FF01">__PETALS__</g></svg>"""

ADMIN_HTML = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ICQ — админка</title><link rel="icon" href="/logo.svg">
<style>
*{box-sizing:border-box}
body{font-family:system-ui,sans-serif;margin:0;background:#f4f6f5;color:#1b1b1b}
header{background:#0b0b0b;color:#fff;padding:12px 16px;font-size:20px;font-weight:600;display:flex;align-items:center;gap:10px}
header img{width:34px;height:34px}
nav{display:flex;gap:6px;padding:10px 12px 0;max-width:820px;margin:0 auto}
nav button{background:#e3e7e5;color:#222;border:0;border-radius:10px 10px 0 0;padding:10px 16px;font-size:15px;cursor:pointer}
nav button.on{background:#fff;color:#00A152;font-weight:600}
main{padding:12px;max-width:820px;margin:0 auto;background:#fff;border-radius:0 12px 12px 12px;min-height:60vh}
.stats{color:#555;font-size:14px;margin:6px 0 14px}
.row{background:#fff;border-radius:12px;padding:12px 14px;margin-bottom:10px;border:1px solid #e3e6e5}
.row.sent{opacity:.55}.row.expired{opacity:.4}.row.banned{background:#fff3f3}
.phone{font-size:18px;font-weight:600}.code{font-size:30px;letter-spacing:4px;font-weight:700;color:#00A152;margin:4px 0}
.meta{font-size:13px;color:#777}
.btns{display:flex;gap:8px;margin-top:8px;flex-wrap:wrap}
a.btn,button.b{background:#00CB66;color:#fff;border:0;border-radius:10px;padding:9px 14px;font-size:14px;text-decoration:none;cursor:pointer}
button.sec{background:#e9ecea;color:#333}button.red{background:#e53935;color:#fff}
.empty{color:#888;text-align:center;padding:30px}
input{width:100%;padding:10px 12px;border:1px solid #d5d9d7;border-radius:10px;font-size:15px;margin-bottom:12px}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#bbb;margin-right:6px}.dot.on{background:#00CB66}
</style></head><body>
<header><img src="/logo.svg" alt="">ICQ — администратор</header>
<nav><button id="t1" class="on" onclick="tab(1)">Коды входа</button><button id="t2" onclick="tab(2)">Пользователи</button><button id="t3" onclick="tab(3)">Группы и каналы</button><button id="t4" onclick="tab(4)">Галочки ✔</button><button id="t5" onclick="tab(5)">Баланс 🪙</button><button id="t6" onclick="tab(6)">Fragment 💎</button><button id="t7" onclick="tab(7)">Серверы 🖥</button></nav>
<main>
<div class="stats" id="stats"></div>
<div id="codes"></div>
<div id="balance" style="display:none">
<p style="opacity:.8">Пополнение баланса (Premium, подарки). Пользователь получит уведомление от «ICQ». Отрицательная сумма — списание.</p>
<input id="bwho" placeholder="ID, телефон или @username"> <input id="bamt" type="number" placeholder="Сумма, Gold" style="width:120px">
<input id="breason" placeholder="Комментарий (необязательно)"> <button onclick="topUp()">Зачислить</button>
<div id="bres" style="margin:10px 0;font-weight:bold"></div><div id="blog"></div></div>
<div id="fragment" style="display:none">
<p style="opacity:.8">Fragment: @имена на продажу за Gold (можно и короткие: 1–3 буквы). Любое другое свободное имя пользователь покупает сам по обычной цене. Цена 0 — снять с продажи.</p>
<input id="fname" placeholder="@имя, например a или icq"> <input id="fprice" type="number" placeholder="Цена, Gold" style="width:120px">
<button onclick="fragAdd()">Выставить</button>
<div id="fres" style="margin:10px 0;font-weight:bold"></div><div id="flist"></div></div>
<div id="servers" style="display:none">
<div class="row"><div class="phone">➕ Täze serwer goş</div>
<p class="meta">Täze VPS-iň IP-si we root paroly ýeterlik: esasy serwer oňa özi girip, ICQ New-i gurup, klastere birikdirýär (3–6 minut). Soňra domende şol IP üçin A ýazgy goş.</p>
<input id="sip" placeholder="IP, mysal: 203.0.113.7" inputmode="decimal">
<div style="display:flex;gap:8px"><input id="suser" placeholder="Ulanyjy" value="root"><input id="sport" type="number" placeholder="SSH port" value="22" style="width:120px"></div>
<input id="spass" type="password" placeholder="SSH paroly" autocomplete="new-password">
<div class="btns"><button class="b" onclick="addSrv()">Goş we gur</button></div><div id="sres" class="meta" style="margin-top:8px"></div></div>
<div id="jlist"></div>
<div id="ninfo" style="margin:8px 0;font-weight:bold"></div><div id="nlist"></div></div>
<div id="users" style="display:none"><input id="q" placeholder="Поиск: имя, телефон, @username или ID" oninput="loadUsers()"><div id="ulist"></div></div>
<div id="groups" style="display:none"><input id="gq" placeholder="Поиск: название, @username или ID" oninput="loadGroups()"><div id="glist"></div></div>
<div id="verify" style="display:none">
<p class="meta">Зелёная галочка ✔ показывается рядом с именем во всех списках и в профиле. Ставит её только администратор.</p>
<div class="row"><div class="phone">Пользователь</div><input id="vu" placeholder="ID, например 10000001" inputmode="numeric">
<div class="btns"><button class="b" onclick="verify('user','vu',true)">Поставить галочку</button><button class="b sec" onclick="verify('user','vu',false)">Снять</button></div></div>
<div class="row"><div class="phone">Группа / канал</div><input id="vg" placeholder="ID группы или канала, например 5" inputmode="numeric">
<div class="btns"><button class="b" onclick="verify('group','vg',true)">Поставить галочку</button><button class="b sec" onclick="verify('group','vg',false)">Снять</button></div></div>
<div id="vres" class="meta"></div></div>
</main>
<script>
var cur=1;
var CHECK='<svg width="16" height="16" viewBox="0 0 24 24" style="vertical-align:-2px;margin-left:4px"><circle cx="12" cy="12" r="11" fill="#00CB66"/><path d="M7 12.5l3.2 3.2L17 9" stroke="#fff" stroke-width="2.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
function tab(n){cur=n;if(n==5)loadBal();if(n==6)loadFrag();if(n==7)loadNodes();['codes','users','groups','verify','balance','fragment','servers'].forEach(function(id,i){document.getElementById('t'+(i+1)).className=n==i+1?'on':'';document.getElementById(id).style.display=n==i+1?'':'none'});
if(n==2)loadUsers();if(n==3)loadGroups();}
function verify(kind,input,on){var id=parseInt(document.getElementById(input).value);if(!id){alert('Введите ID');return}
fetch('/admin/api/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({kind:kind,id:id,on:on})}).then(function(r){return r.json().then(function(j){return [r.ok,j]})}).then(function(x){
document.getElementById('vres').textContent=x[0]?((on?'✔ Галочка поставлена: ':'Галочка снята: ')+x[1].name):(x[1].detail||'Ошибка')})}
function grow(g){var d=el('div','row');var p=el('div','phone');p.innerHTML='';p.appendChild(document.createTextNode((g.kind=='channel'?'📢 ':'👥 ')+g.name));if(g.verified)p.insertAdjacentHTML('beforeend',CHECK);d.appendChild(p);
d.appendChild(el('div','meta','ID '+g.id+(g.username?' · @'+g.username:'')+' · '+(g.kind=='channel'?'подписчиков: ':'участников: ')+g.count+(g.public?' · публичный':'')+' · создатель ID '+g.owner));
var b=el('div','btns');var v=el('button','b '+(g.verified?'sec':''),g.verified?'Снять галочку':'Поставить галочку ✔');
v.onclick=function(){fetch('/admin/api/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({kind:'group',id:g.id,on:!g.verified})}).then(loadGroups)};b.appendChild(v);
var x=el('button','b sec','Удалить 🗑');x.onclick=function(){if(confirm('Удалить «'+g.name+'» у всех?'))fetch('/admin/api/groups/'+g.id,{method:'DELETE'}).then(loadGroups)};b.appendChild(x);
d.appendChild(b);return d}
function loadGroups(){var q=document.getElementById('gq').value;fetch('/admin/api/groups?q='+encodeURIComponent(q)).then(function(r){return r.json()}).then(function(list){var l=document.getElementById('glist');l.innerHTML='';if(!list.length){l.appendChild(el('div','empty','Ничего нет'));return}list.forEach(function(g){l.appendChild(grow(g))})})}
function topUp(){var b={who:document.getElementById('bwho').value,amount:parseInt(document.getElementById('bamt').value||'0'),reason:document.getElementById('breason').value};
fetch('/admin/api/balance',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}).then(function(r){return r.json().then(function(j){return [r.ok,j]})}).then(function(x){var e=document.getElementById('bres');
if(x[0]){e.textContent='✅ '+x[1].name+': баланс '+x[1].balance+' Gold';document.getElementById('bamt').value='';loadBal()}else{e.textContent='❌ '+(x[1].detail||'Ошибка')}})}
function fragAdd(){var b={username:document.getElementById('fname').value,price:parseInt(document.getElementById('fprice').value||'0')};
fetch('/admin/api/fragment',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}).then(function(r){return r.json().then(function(j){return [r.ok,j]})}).then(function(x){var e=document.getElementById('fres');
if(x[0]){e.textContent=x[1].removed?('🗑 @'+x[1].removed+' снято с продажи'):('✅ @'+x[1].username+' — '+x[1].price+' Gold');document.getElementById('fname').value='';loadFrag()}else{e.textContent='❌ '+(x[1].detail||'Ошибка')}})}
var jobTimer=null;
function addSrv(){var b={ip:document.getElementById('sip').value.trim(),user:document.getElementById('suser').value.trim()||'root',port:parseInt(document.getElementById('sport').value||'22'),password:document.getElementById('spass').value};
var e=document.getElementById('sres');e.textContent='…';
fetch('/admin/api/servers',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}).then(function(r){return r.json().then(function(j){return [r.ok,j]})}).then(function(x){
if(x[0]){e.textContent=x[1].already?'Bu serwer eýýäm gurulýar':'Başlady ✔ — aşakda ýazgylary görüň';document.getElementById('spass').value='';loadJobs()}else{e.textContent='❌ '+(x[1].detail||'Ýalňyşlyk')}}).catch(function(){e.textContent='❌ Baglanyşyk ýok'})}
function loadJobs(){fetch('/admin/api/servers/jobs').then(function(r){return r.json()}).then(function(list){var l=document.getElementById('jlist');l.innerHTML='';var busy=false;
list.slice(0,5).forEach(function(j){var d=el('div','row');var ico={queued:'⏳',running:'⏳',done:'✅',failed:'❌'}[j.status]||'';if(j.status=='queued'||j.status=='running')busy=true;
d.appendChild(el('div','phone',ico+' '+j.ip+' · '+({queued:'nobatda',running:'gurulýar…',done:'goşuldy',failed:'şowsuz'}[j.status]||j.status)));d.appendChild(el('div','meta',fmt(j.updated)));
var pre=el('pre','',j.log.split('\\n').slice(-(busy?40:12)).join('\\n'));pre.style.cssText='background:#111;color:#cfe;padding:8px;border-radius:8px;font-size:12px;white-space:pre-wrap;max-height:260px;overflow:auto';d.appendChild(pre);l.appendChild(d)});
clearTimeout(jobTimer);if(busy&&cur==7)jobTimer=setTimeout(function(){loadJobs();loadNodes()},3000)})}
function rmSrv(n){if(!confirm('«'+n.id+'» serwerini klasterden aýyrmalymy? Ol bazany ulanyp bilmez. Soňra domenden onuň A ýazgysyny poz.'))return;
fetch('/admin/api/servers/'+encodeURIComponent(n.id),{method:'DELETE'}).then(function(r){return r.json()}).then(function(j){if(j.dns)alert(j.dns);loadNodes()})}
function loadNodes(){loadJobs();fetch('/admin/api/cluster').then(function(r){return r.json()}).then(function(j){
document.getElementById('ninfo').textContent=(j.enabled?'Klaster: '+j.nodes.length+' serwer':'Bir serwer (klaster ýok)')+' · baza: '+j.db+' · onlaýn: '+j.online;
var l=document.getElementById('nlist');l.innerHTML='';j.nodes.forEach(function(n){var d=el('div','row');
d.appendChild(el('div','phone',(n.alive?'🟢 ':'🔴 ')+n.id+(n.me?' (şu)':'')+(j.leader==n.id?' · lider':'')));
var st=n.stats||{};function gb(b){return b?(b/1073741824).toFixed(1)+' GB':'—'}function pc(u,t){return t?Math.round(100*u/t)+'%':'—'}
d.appendChild(el('div','',(n.url||'')+' · onlaýn: '+n.online+(n.alive?'':' · IŞLEMEÝÄR')));
d.appendChild(el('div','','CPU '+(st.cpu!=null?st.cpu+'%':'—')+' ('+(st.cores||'?')+' ýadro, load '+((st.load||[])[0]||0)+') · RAM '+gb(st.mem_used)+' / '+gb(st.mem_total)+' ('+pc(st.mem_used,st.mem_total)+') · Disk '+gb(st.disk_used)+' / '+gb(st.disk_total)+' ('+pc(st.disk_used,st.disk_total)+')'));
d.appendChild(el('div','','Tor: ↓'+((st.net_rx||0)/1024).toFixed(0)+' KB/s ↑'+((st.net_tx||0)/1024).toFixed(0)+' KB/s · işläp dur: '+Math.floor((st.uptime||0)/86400)+' gün · faýllar '+gb(st.files_bytes)));
if(n.id!='main'&&!n.me){var b=el('div','btns');var x=el('button','b red','Klasterden aýyr');x.onclick=function(){rmSrv(n)};b.appendChild(x);d.appendChild(b)}
l.appendChild(d)});
if(!j.can_add)document.getElementById('sres').innerHTML='ℹ️ Ilki esasy serwerde bir gezek: <code>cd /opt/icq-server && bash cluster-main.sh</code> (PostgreSQL + Redis + häzirki maglumatlary göçürýär).'})}
function loadFrag(){fetch('/admin/api/fragment').then(function(r){return r.json()}).then(function(list){var l=document.getElementById('flist');l.innerHTML='';
list.forEach(function(it){var d=el('div','row');d.appendChild(el('div','phone','@'+it.username+' · '+it.price+' Gold'));d.appendChild(el('div','',it.sold_to?('продано: '+(it.buyer||it.sold_to)+' · '+fmt(it.sold_ts)):'в продаже'));l.appendChild(d)})})}
function loadBal(){fetch('/admin/api/balance').then(function(r){return r.json()}).then(function(list){var l=document.getElementById('blog');l.innerHTML='';
list.forEach(function(it){var d=el('div','row');d.appendChild(el('div','phone',(it.delta>0?'+':'')+it.delta+' Gold · '+(it.name||it.uin)));d.appendChild(el('div','',it.reason+' · '+fmt(it.ts)));l.appendChild(d)})})}
function el(t,c,txt){var e=document.createElement(t);if(c)e.className=c;if(txt!=null)e.textContent=txt;return e}
function row(it){
  var d=el('div','row'+(it.sent?' sent':'')+(it.expired?' expired':''));
  var msg='Код для входа в ICQ: '+it.code;
  d.appendChild(el('div','phone','+'+it.phone));
  d.appendChild(el('div','code',it.code));
  d.appendChild(el('div','meta',(it.expired?'истёк · ':'')+(it.sent?'отправлено · ':'')+Math.floor(it.age/60)+' мин '+(it.age%60)+' с назад'));
  var b=el('div','btns');
  var sms=el('a','btn','SMS');sms.href='sms:+'+it.phone+'?body='+encodeURIComponent(msg);b.appendChild(sms);
  var wa=el('a','btn','WhatsApp');wa.href='https://wa.me/'+it.phone+'?text='+encodeURIComponent(msg);wa.target='_blank';b.appendChild(wa);
  var ok=el('button','b sec','Отправлено');ok.onclick=function(){fetch('/admin/api/sent/'+it.phone,{method:'POST'}).then(load)};b.appendChild(ok);
  d.appendChild(b);return d;
}
function load(){
  fetch('/admin/api/pending').then(function(r){return r.json()}).then(function(j){
    document.getElementById('stats').textContent='Пользователей: '+j.users+' · Сообщений: '+j.messages+' · Групп: '+j.groups+' · Каналов: '+j.channels+' · Онлайн: '+j.online;
    var l=document.getElementById('codes');l.innerHTML='';
    if(!j.items.length){l.appendChild(el('div','empty','Запросов кода пока нет'));return}
    j.items.forEach(function(it){l.appendChild(row(it))});
  });
}
function fmt(ms){if(!ms)return '—';var d=new Date(ms);return d.toLocaleString('ru-RU')}
function urow(u){
  var d=el('div','row'+(u.banned?' banned':''));
  var p=el('div','phone');var dot=el('span','dot'+(u.online?' on':''));p.appendChild(dot);p.appendChild(document.createTextNode(u.name));if(u.verified)p.insertAdjacentHTML('beforeend',CHECK);p.appendChild(document.createTextNode('  ·  ID '+u.uin+(u.username?'  ·  @'+u.username:'')));d.appendChild(p);
  d.appendChild(el('div','meta','+'+u.phone+' · сообщений: '+u.messages+' · регистрация: '+fmt(u.created)+' · был(а): '+(u.online?'в сети':fmt(u.last_seen))+(u.banned?' · ЗАБЛОКИРОВАН':'')));
  var b=el('div','btns');
  var ban=el('button','b '+(u.banned?'':'sec'),u.banned?'Разблокировать':'Заблокировать');
  ban.onclick=function(){fetch('/admin/api/users/'+u.uin+'/ban?ban='+(!u.banned),{method:'POST'}).then(loadUsers)};b.appendChild(ban);
  var vv=el('button','b '+(u.verified?'sec':''),u.verified?'Снять галочку':'Галочка ✔');
  vv.onclick=function(){fetch('/admin/api/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({kind:'user',id:u.uin,on:!u.verified})}).then(loadUsers)};b.appendChild(vv);
  var del=el('button','b red','Удалить');
  del.onclick=function(){if(confirm('Удалить '+u.name+' и всю его переписку?'))fetch('/admin/api/users/'+u.uin,{method:'DELETE'}).then(loadUsers)};b.appendChild(del);
  d.appendChild(b);return d;
}
function loadUsers(){
  var q=document.getElementById('q').value;
  fetch('/admin/api/users?q='+encodeURIComponent(q)).then(function(r){return r.json()}).then(function(list){
    var l=document.getElementById('ulist');l.innerHTML='';
    if(!list.length){l.appendChild(el('div','empty','Никого не найдено'));return}
    list.forEach(function(u){l.appendChild(urow(u))});
  });
}
load();setInterval(function(){if(cur==1)load()},4000);
</script></body></html>"""


def _petals_svg() -> str:
    return '<path d="M64.67,34.04 L57.86,44.43 A4.62,4.62 0 0,1 50.14,44.43 L43.33,34.04 A12.76,12.76 0 1,1 64.67,34.04 Z M76.28,57.98 L64.29,54.72 A4.62,4.62 0 0,1 61.91,47.37 L69.68,37.68 A12.76,12.76 0 1,1 76.28,57.98 Z M57.10,76.42 L56.50,64.01 A4.62,4.62 0 0,1 62.75,59.47 L74.36,63.87 A12.76,12.76 0 1,1 57.10,76.42 Z M33.64,63.87 L45.25,59.47 A4.62,4.62 0 0,1 51.50,64.01 L50.90,76.42 A12.76,12.76 0 1,1 33.64,63.87 Z M38.32,37.68 L46.09,47.37 A4.62,4.62 0 0,1 43.71,54.72 L31.72,57.98 A12.76,12.76 0 1,1 38.32,37.68 Z"/>'


_LOGO = LOGO_SVG.replace("__PETALS__", _petals_svg())


_BOT_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2EE57A"/><stop offset=".5" stop-color="#0A8F55"/><stop offset="1" stop-color="#066B44"/></linearGradient></defs>
<circle cx="50" cy="50" r="50" fill="url(#g)"/><circle cx="30" cy="25" r="42" fill="#fff" opacity=".12"/>
<line x1="50" y1="38" x2="50" y2="24" stroke="#fff" stroke-width="3.5"/>
<g fill="#22FF01"><circle cx="50" cy="15.5" r="3.5"/><circle cx="54.3" cy="18.6" r="3.5"/><circle cx="52.6" cy="23.6" r="3.5"/><circle cx="47.4" cy="23.6" r="3.5"/><circle cx="45.7" cy="18.6" r="3.5"/></g>
<circle cx="50" cy="20" r="2" fill="#0A8F55"/>
<rect x="17" y="50" width="7" height="17" rx="3" fill="#fff"/><rect x="76" y="50" width="7" height="17" rx="3" fill="#fff"/>
<rect x="22" y="38" width="56" height="42" rx="12" fill="#fff"/><rect x="28.7" y="44.7" width="42.6" height="27.7" rx="8" fill="#0B3D2A"/>
<circle cx="40.5" cy="55.3" r="4.5" fill="#22FF01"/><circle cx="59.5" cy="55.3" r="4.5" fill="#22FF01"/>
<path d="M43 61.5 Q50 68 57 61.5" stroke="#22FF01" stroke-width="2.5" fill="none" stroke-linecap="round"/></svg>"""


@app.get("/bot.svg")
def bot_svg() -> Response:
    return Response(_BOT_SVG, media_type="image/svg+xml", headers={"Cache-Control": "max-age=86400"})


@app.get("/logo.svg")
def logo() -> Response:
    return Response(_LOGO, media_type="image/svg+xml")


@app.get("/admin", response_class=HTMLResponse)
def admin_page(_: None = Depends(require_admin)) -> str:
    return ADMIN_HTML


# -------------------------------------------------------------------- websocket
async def broadcast_presence(uin: int, online: bool) -> None:
    last_seen = now_ms()
    with conn() as c:
        if not online:
            c.execute("UPDATE users SET last_seen=? WHERE uin=?", (last_seen, uin))
        u = c.execute("SELECT show_online FROM users WHERE uin=?", (uin,)).fetchone()
        if u is None:
            return
        targets = related_uins(c, uin)
    shown = last_seen if u["show_online"] else 0
    await manager.send_many(targets, {"type": "presence", "uin": uin, "online": online, "last_seen": shown})


def _int(v) -> Optional[int]:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


async def fail(ws: WebSocket, client_id: str, text: str) -> None:
    await ws.send_text(json.dumps({"type": "failed", "client_id": client_id, "text": text}, ensure_ascii=False))


async def handle_message(sender: int, ws: WebSocket, data: dict) -> None:
    to = _int(data.get("to"))
    gid = _int(data.get("group"))
    kind = str(data.get("kind") or "text")
    if kind not in KINDS:
        kind = "text"
    text = str(data.get("text") or "").strip()[:4000]
    client_id = str(data.get("client_id") or "")[:64]
    if not client_id or (to is None and gid is None):
        return
    reply_to = _int(data.get("reply_to"))
    fwd_name = (str(data.get("fwd_name"))[:60] if data.get("fwd_name") else None)
    att_id = att_name = att_mime = None
    att_size = att_dur = 0
    poll_cfg = None
    with conn() as c:
        existing = c.execute(
            "SELECT * FROM messages WHERE from_uin=? AND client_id=?", (sender, client_id)
        ).fetchone()
        if existing:  # re-sent after reconnect: just re-ack, do not duplicate
            await ws.send_text(
                json.dumps({"type": "ack", "client_id": client_id, "id": existing["id"], "ts": existing["ts"]})
            )
            return
        if gid is not None:
            g = c.execute("SELECT kind FROM groups WHERE id=?", (gid,)).fetchone()
            if not g or not is_member(c, gid, sender):
                await fail(ws, client_id, "Вы не участник этого чата")
                return
            admin = is_admin(c, gid, sender)
            if kind == "text" and text.split(" ", 1)[0].split("\n", 1)[0].lower() in COMMANDS:
                if not admin and text.split()[0].lower() != ".help":
                    await fail(ws, client_id, "Команды доступны только администраторам")
                    return
                cmd_args = (text, reply_to)
            else:
                cmd_args = None
            if cmd_args is None:
                if g["kind"] == "channel" and not admin:
                    await fail(ws, client_id, "В канале пишут только администраторы")
                    return
                left = muted_left(c, gid, sender)
                if left:
                    await fail(
                        ws, client_id,
                        "Вам запрещено писать в этом чате" + ("" if left < 0 else f" ещё {max(1, left // 60000)} мин"),
                    )
                    return
            if cmd_args is None and not admin:
                gs = group_settings(c.execute("SELECT * FROM groups WHERE id=?", (gid,)).fetchone())
                if kind in ATT_KINDS and not gs["members_media"]:
                    await fail(ws, client_id, "В этой группе участникам нельзя отправлять файлы и фото")
                    return
                if gs["slow_mode"]:
                    last = c.execute(
                        "SELECT MAX(ts) AS t FROM messages WHERE group_id=? AND from_uin=?", (gid, sender)
                    ).fetchone()["t"] or 0
                    wait = gs["slow_mode"] * 1000 - (now_ms() - last)
                    if wait > 0:
                        await fail(ws, client_id, f"Медленный режим: подождите {wait // 1000 + 1} с")
                        return
            to = 0
        elif to == SERVICE_UIN:
            await fail(ws, client_id, "Это служебный чат ICQ: сюда нельзя писать")
            return
        elif to == sender:
            cmd_args = None  # "Saved messages": a chat with yourself
        else:
            cmd_args = None
            if not c.execute("SELECT 1 FROM users WHERE uin=? AND banned=0", (to,)).fetchone():
                await fail(ws, client_id, "Пользователь не найден")
                return
            if is_blocked(c, to, sender):
                await fail(ws, client_id, "Пользователь ограничил получение сообщений")
                return
            if is_blocked(c, sender, to):
                await fail(ws, client_id, "Вы заблокировали этого пользователя")
                return
        if cmd_args is not None:
            pass
        elif kind in ATT_KINDS:
            f = can_access_file(c, sender, str(data.get("att_id") or ""))
            if not f:
                await fail(ws, client_id, "Файл недоступен")
                return
            att_id, att_name, att_mime, att_size, att_dur = f["id"], f["name"], f["mime"], f["size"], f["dur"]
        elif kind == "poll":
            raw = data.get("poll") or {}
            opts = [str(o).strip()[:100] for o in (raw.get("options") or []) if str(o).strip()][:10]
            if not text or len(opts) < 2:
                await fail(ws, client_id, "В опросе нужен вопрос и минимум 2 варианта")
                return
            poll_cfg = (opts, bool(raw.get("multiple")), raw.get("anonymous", True) is not False)
        elif not text:
            return
        if cmd_args is not None:
            row = None
        elif reply_to is not None:
            r = c.execute("SELECT group_id, from_uin, to_uin FROM messages WHERE id=?", (reply_to,)).fetchone()
            if not r or (gid is not None and r["group_id"] != gid) or (
                gid is None and {r["from_uin"], r["to_uin"]} != {sender, to}
            ):
                reply_to = None
    if cmd_args is not None:
        await handle_command(sender, ws, gid, cmd_args[0], cmd_args[1], client_id)
        return
    with conn() as c:
        ttl = chat_ttl_of(c, sender, to, gid)
        ts = now_ms()
        cur = c.execute(
            "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, att_id, att_name, att_size, att_mime,"
            " att_dur, group_id, reply_to, fwd_name, rev, expire_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (client_id, sender, to, text, ts, kind, att_id, att_name, att_size, att_mime, att_dur,
             gid, reply_to, fwd_name, next_rev(c), ts + ttl * 1000 if ttl else 0),
        )
        if kind == "poll":
            c.execute("INSERT INTO polls(msg_id, options, multiple, anonymous) VALUES(?,?,?,?)",
                      (cur.lastrowid, json.dumps(poll_cfg[0], ensure_ascii=False), int(poll_cfg[1]), int(poll_cfg[2])))
        if gid is None and to != sender:
            c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (sender, to))
            c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (to, sender))
        row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
        msg = msg_json(c, row)
        targets = message_targets(c, row)
        push_message(c, row, targets)
    await ws.send_text(json.dumps({"type": "ack", "client_id": client_id, "id": msg["id"], "ts": msg["ts"]}))
    await manager.send_many(targets, {"type": "message", "message": msg}, skip=ws)
    if gid is None and to == BOT_UIN and sender != BOT_UIN:
        asyncio.create_task(bot_answer(sender, text, kind))


def build_update(c: sqlite3.Connection, msg_id: int):
    row = c.execute("SELECT * FROM messages WHERE id=?", (msg_id,)).fetchone()
    return message_targets(c, row), {"type": "update", "message": msg_json(c, row)}


async def handle_edit(uin: int, data: dict) -> None:
    mid = _int(data.get("id"))
    text = str(data.get("text") or "").strip()[:4000]
    if mid is None or not text:
        return
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        if not r or r["from_uin"] != uin or r["kind"] != "text" or r["deleted"]:
            return
        c.execute("UPDATE messages SET text=?, edited=1, rev=? WHERE id=?", (text, next_rev(c), mid))
        targets, payload = build_update(c, mid)
    await manager.send_many(targets, payload)


async def handle_delete(uin: int, data: dict) -> None:
    mid = _int(data.get("id"))
    if mid is None:
        return
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        if not r or r["deleted"] or r["kind"] == "system":
            return
        if r["from_uin"] != uin:
            gid = r["group_id"]
            if not gid or not is_admin(c, gid, uin) or role_of(c, gid, r["from_uin"]) == "owner":
                return
        gone = False
        if r["group_id"]:
            gk = c.execute("SELECT kind FROM groups WHERE id=?", (r["group_id"],)).fetchone()
            gone = bool(gk and gk["kind"] == "channel")  # channel posts vanish without "message deleted"
        c.execute(
            "UPDATE messages SET text='', deleted=1, kind=?, att_id=NULL, att_name=NULL, att_size=0,"
            " att_mime=NULL, att_dur=0, rev=? WHERE id=?",
            ("gone" if gone else "text", next_rev(c), mid),
        )
        c.execute("DELETE FROM reactions WHERE msg_id=?", (mid,))
        c.execute("DELETE FROM poll_votes WHERE msg_id=?", (mid,))
        c.execute("DELETE FROM polls WHERE msg_id=?", (mid,))
        targets, payload = build_update(c, mid)
    await manager.send_many(targets, payload)


async def handle_react(uin: int, data: dict) -> None:
    mid = _int(data.get("id"))
    emoji = str(data.get("emoji") or "")
    if mid is None:
        return
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        if not r or r["deleted"] or r["kind"] == "system":
            return
        if r["group_id"]:
            if not is_member(c, r["group_id"], uin):
                return
            gs = group_settings(c.execute("SELECT * FROM groups WHERE id=?", (r["group_id"],)).fetchone())
            if not gs["reactions"]:
                return
        elif uin not in (r["from_uin"], r["to_uin"]):
            return
        old = c.execute("SELECT emoji FROM reactions WHERE msg_id=? AND uin=?", (mid, uin)).fetchone()
        if not emoji or emoji not in REACTIONS or (old and old["emoji"] == emoji):
            c.execute("DELETE FROM reactions WHERE msg_id=? AND uin=?", (mid, uin))  # toggle off
        else:
            c.execute("INSERT OR REPLACE INTO reactions(msg_id, uin, emoji) VALUES(?,?,?)", (mid, uin, emoji))
        c.execute("UPDATE messages SET rev=? WHERE id=?", (next_rev(c), mid))
        targets, payload = build_update(c, mid)
    await manager.send_many(targets, payload)


async def handle_played(uin: int, data: dict) -> None:
    """The recipient listened to a voice message / watched a round video: the dot disappears for everybody."""
    mid = _int(data.get("id"))
    if mid is None:
        return
    with conn() as c:
        r = c.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        if not r or r["from_uin"] == uin or r["kind"] not in ("voice", "video_note") or _col(r, "played", 0):
            return
        if r["group_id"]:
            if not is_member(c, r["group_id"], uin):
                return
        elif r["to_uin"] != uin:
            return
        c.execute("UPDATE messages SET played=1, rev=? WHERE id=?", (next_rev(c), mid))
        targets, payload = build_update(c, mid)
    await manager.send_many(targets, payload)


async def handle_read(reader: int, data: dict) -> None:
    gid = _int(data.get("group"))
    if gid is not None:
        with conn() as c:
            if not is_member(c, gid, reader):
                return
            upto = c.execute("SELECT COALESCE(MAX(id), 0) AS m FROM messages WHERE group_id=?", (gid,)).fetchone()["m"]
            cur = c.execute(
                "UPDATE group_members SET read_upto=? WHERE gid=? AND uin=? AND read_upto<?", (upto, gid, reader, upto)
            )
            if not cur.rowcount:
                return
            targets = [m for m in group_members(c, gid) if m != reader]
        await manager.send_many(targets, {"type": "read", "gid": gid, "uin": reader, "upto": upto})
        return
    peer = _int(data.get("to"))
    if peer is None:
        return
    with conn() as c:
        cur = c.execute(
            "UPDATE messages SET is_read=1 WHERE to_uin=? AND from_uin=? AND is_read=0 AND group_id IS NULL",
            (reader, peer),
        )
        if not cur.rowcount:
            return
        upto = c.execute(
            "SELECT MAX(id) AS m FROM messages WHERE to_uin=? AND from_uin=? AND is_read=1 AND group_id IS NULL",
            (reader, peer),
        ).fetchone()["m"]
    await manager.send(peer, {"type": "read", "uin": reader, "upto": upto})


async def handle_typing(uin: int, data: dict) -> None:
    gid = _int(data.get("group"))
    # "" = typing, "voice" = recording a voice message, "video" = recording a round video
    action = str(data.get("action") or "")
    if action not in ("voice", "video"):
        action = ""
    with conn() as c:
        if gid is not None:
            if not is_member(c, gid, uin):
                return
            targets = [m for m in group_members(c, gid) if m != uin]
            payload = {"type": "typing", "gid": gid, "uin": uin, "action": action}
        else:
            to = _int(data.get("to"))
            if to is None or is_blocked(c, to, uin):
                return
            targets = [to]
            payload = {"type": "typing", "uin": uin, "action": action}
    await manager.send_many(targets, payload)


# ------------------------------------------------------------------ ICQ bot
BOT_HELP = (
    "Я — ICQ Бот 🤖\n\n"
    "/id — ваш ID (/id @имя — чужой)\n"
    "/me — ваш профиль\n"
    "/username — как получить @имя\n"
    "/groups — как создать группу или канал\n"
    "/stories — как сделать публикацию\n"
    "/community — ICQ Community: общий чат пользователей\n"
    "/help — эта справка\n\n"
    "💬 ICQ Community: https://icq-new.online/@icqcommunity"
)
COMMUNITY_USERNAME = "icqcommunity"


def bot_text_for(c: sqlite3.Connection, uin: int, text: str, kind: str) -> str:
    t = (text or "").strip()
    low = t.lower()
    u = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
    if kind != "text":
        return "Спасибо! 😊 Я пока понимаю только текстовые команды. Напишите /help"
    if low in ("/start", "start"):
        return f"Привет, {u['name']}! 👋\nДобро пожаловать в ICQ New.\n\n" + BOT_HELP
    if low in ("/help", "help", "помощь", "?"):
        return BOT_HELP
    if low in ("/community", "community", "сообщество"):
        g = c.execute("SELECT id FROM groups WHERE username=?", (COMMUNITY_USERNAME,)).fetchone()
        n = c.execute("SELECT COUNT(*) AS n FROM group_members WHERE gid=?", (g["id"],)).fetchone()["n"] if g else 0
        return (
            "💬 **ICQ Community** — общий чат пользователей ICQ New: новости, вопросы, идеи и знакомства.\n"
            f"Участников: {n}\n\n"
            "Вступить: https://icq-new.online/@icqcommunity"
        )
    if low == "/id":
        return f"🆔 Ваш ID: {uin}\n\nЧужой ID: /id @имя"
    if low.startswith("/id ") or (low.startswith("@") and " " not in low):
        name = t.split(None, 1)[-1].strip().lstrip("@")
        x = c.execute("SELECT uin, name FROM users WHERE username=? COLLATE NOCASE AND banned=0", (name,)).fetchone()
        g = None if x else c.execute("SELECT id, name, kind FROM groups WHERE username=? COLLATE NOCASE", (name,)).fetchone()
        if x:
            return f"🆔 {x['name']} (@{name}): {x['uin']}"
        if g:
            return f"🆔 {'Канал' if g['kind'] == 'channel' else 'Группа'} «{g['name']}» (@{name}): {g['id']}"
        return f"Не нашёл @{name} 🤷"
    if low == "/me":
        return (
            f"👤 {u['name']}{' ✔' if u['verified'] else ''}\n"
            f"ID: {uin}\n"
            f"@имя: {('@' + u['username']) if u['username'] else 'не задано'}\n"
            f"О себе: {u['about'] or '—'}"
        )
    if low == "/username":
        return "Откройте Настройки → Аккаунт → Имя пользователя и задайте @имя. По нему вас найдут через поиск."
    if low == "/groups":
        return ("Нажмите ✏️ в списке чатов → «Новая группа» или «Новый канал». "
                "В настройках группы можно поменять имя и фото бота, включить медленный режим и другое.")
    if low == "/stories":
        return "Нажмите «Моя публикация» вверху списка чатов, выберите фото и добавьте подпись. Публикация видна 24 часа."
    if any(w in low for w in ("салам", "привет", "salam", "hello", "hi", "здравств")):
        return f"Привет, {u['name']}! 😊 Чем помочь? Напишите /help"
    if any(w in low for w in ("спасибо", "sag bol", "sagbol", "thanks", "thank")):
        return "Пожалуйста! 🙌"
    return "Я не понял 🤔 Напишите /help, чтобы увидеть, что я умею."


async def service_send(uin: int, text: str) -> None:
    """A message from the «ICQ» service account (login codes, new devices)."""
    with conn() as c:
        c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (uin, SERVICE_UIN))
        cur = c.execute(
            "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, rev) VALUES(?,?,?,?,?,'text',?)",
            ("svc-" + secrets.token_hex(8), SERVICE_UIN, uin, text[:4000], now_ms(), next_rev(c)),
        )
        row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
        msg = msg_json(c, row)
        push_message(c, row, [uin])
    await manager.send(uin, {"type": "message", "message": msg})


def client_ip(request: Optional[Request]) -> str:
    if request is None:
        return ""
    h = request.headers
    ip = (h.get("x-forwarded-for") or "").split(",")[0].strip() or h.get("x-real-ip") or (request.client.host if request.client else "")
    return ip


async def bot_send(uin: int, text: str) -> None:
    with conn() as c:
        c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (uin, BOT_UIN))
        c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (BOT_UIN, uin))
        cur = c.execute(
            "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, rev) VALUES(?,?,?,?,?,'text',?)",
            ("botdm-" + secrets.token_hex(8), BOT_UIN, uin, text[:4000], now_ms(), next_rev(c)),
        )
        row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
        msg = msg_json(c, row)
        push_message(c, row, [uin])
    await manager.send(uin, {"type": "message", "message": msg})


async def bot_answer(uin: int, text: str, kind: str) -> None:
    try:
        await asyncio.sleep(0.4)
        with conn() as c:  # the bot "reads" the message
            c.execute("UPDATE messages SET is_read=1 WHERE from_uin=? AND to_uin=? AND is_read=0", (uin, BOT_UIN))
            upto = c.execute(
                "SELECT MAX(id) AS m FROM messages WHERE from_uin=? AND to_uin=?", (uin, BOT_UIN)
            ).fetchone()["m"]
            reply = bot_text_for(c, uin, text, kind)
        await manager.send(uin, {"type": "read", "uin": BOT_UIN, "upto": upto})
        await manager.send(uin, {"type": "typing", "uin": BOT_UIN})
        await asyncio.sleep(min(2.5, 0.6 + len(reply) / 120))
        await bot_send(uin, reply)
    except Exception as e:
        print(f"[bot] {e!r}", flush=True)


async def bot_welcome_if_needed(uin: int) -> None:
    if uin == BOT_UIN:
        return
    with conn() as c:
        seen = c.execute("SELECT 1 FROM messages WHERE from_uin=? AND to_uin=? LIMIT 1", (BOT_UIN, uin)).fetchone()
        name = uname(c, uin)
    if not seen:
        await bot_send(uin, f"Привет, {name}! 👋 Я — ICQ Бот.\n\n" + BOT_HELP)


# --------------------------------------------------------------------- stories
STORY_TTL_MS = 24 * 3600 * 1000


class StoryIn(BaseModel):
    att_id: str
    text: str = ""


def story_json(c: sqlite3.Connection, r: sqlite3.Row, viewer: int) -> dict:
    f = c.execute("SELECT mime FROM files WHERE id=?", (r["att_id"],)).fetchone()
    ro = _col(r, "repost_uin")
    rn = c.execute("SELECT name FROM users WHERE uin=?", (ro,)).fetchone() if ro else None
    d = {
        "mime": f["mime"] if f else "image/jpeg",
        "repost_of": _col(r, "repost_of"),
        "repost_name": rn["name"] if rn else None,
        "id": r["id"],
        "uin": r["uin"],
        "att_id": r["att_id"],
        "text": r["text"],
        "ts": r["ts"],
    }
    v = c.execute("SELECT reaction FROM story_views WHERE story=? AND uin=?", (r["id"], viewer)).fetchone()
    d["seen"] = v is not None
    d["my_reaction"] = (v["reaction"] if v else None) or ""
    if r["uin"] == viewer:
        d["views"] = c.execute("SELECT COUNT(*) AS n FROM story_views WHERE story=?", (r["id"],)).fetchone()["n"]
        d["reactions"] = c.execute(
            "SELECT COUNT(*) AS n FROM story_views WHERE story=? AND reaction IS NOT NULL AND reaction<>''", (r["id"],)
        ).fetchone()["n"]
    d["comments"] = c.execute("SELECT COUNT(*) AS n FROM story_comments WHERE story=?", (r["id"],)).fetchone()["n"]
    return d


@app.get("/api/users/{other}/publications")
def publications(other: int, kind: str = Query("image"), authorization: Optional[str] = Header(None)) -> list:
    """Profile → Публикации: Картинки (kind=image), Видео (video), Репост (repost) — all time, newest first."""
    uin = require_uin(authorization)
    with conn() as c:
        if kind == "repost":
            rows = c.execute("SELECT * FROM stories WHERE uin=? AND repost_of IS NOT NULL ORDER BY id DESC LIMIT 200",
                             (other,)).fetchall()
        else:
            prefix = "video/%" if kind == "video" else "image/%"
            rows = c.execute(
                "SELECT s.* FROM stories s JOIN files f ON f.id=s.att_id WHERE s.uin=? AND s.repost_of IS NULL"
                " AND f.mime LIKE ? ORDER BY s.id DESC LIMIT 200", (other, prefix),
            ).fetchall()
        return [story_json(c, r, uin) for r in rows]


@app.post("/api/stories/{sid}/repost")
async def repost_story(sid: int, authorization: Optional[str] = Header(None)) -> dict:
    """Repost somebody's publication to my profile (and to the ring for 24 h)."""
    uin = require_uin(authorization)
    with conn() as c:
        s = c.execute("SELECT * FROM stories WHERE id=?", (sid,)).fetchone()
        if not s:
            raise HTTPException(404, "Публикация не найдена")
        if s["uin"] == uin:
            raise HTTPException(400, "Это ваша публикация")
        orig = _col(s, "repost_of") or s["id"]
        orig_uin = _col(s, "repost_uin") or s["uin"]
        if c.execute("SELECT 1 FROM stories WHERE uin=? AND repost_of=?", (uin, orig)).fetchone():
            raise HTTPException(400, "Вы уже сделали репост")
        cur = c.execute(
            "INSERT INTO stories(uin, att_id, text, ts, repost_of, repost_uin) VALUES(?,?,?,?,?,?)",
            (uin, s["att_id"], s["text"], now_ms(), orig, orig_uin),
        )
        row = c.execute("SELECT * FROM stories WHERE id=?", (cur.lastrowid,)).fetchone()
        result = story_json(c, row, uin)
        targets = related_uins(c, uin)
    await manager.send_many(targets + [uin], {"type": "stories"})
    return result


@app.post("/api/stories")
async def add_story(body: StoryIn, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        f = c.execute("SELECT * FROM files WHERE id=? AND owner=?", (body.att_id, uin)).fetchone()
        if not f or not (f["mime"].startswith("image/") or f["mime"].startswith("video/")):
            raise HTTPException(400, "Для публикации нужно фото или видео")
        cur = c.execute(
            "INSERT INTO stories(uin, att_id, text, ts) VALUES(?,?,?,?)", (uin, body.att_id, body.text.strip()[:300], now_ms())
        )
        row = c.execute("SELECT * FROM stories WHERE id=?", (cur.lastrowid,)).fetchone()
        result = story_json(c, row, uin)
        targets = related_uins(c, uin)
    await manager.send_many(targets + [uin], {"type": "stories"})
    return result


@app.get("/api/stories")
def list_stories(authorization: Optional[str] = Header(None)) -> list:
    """Stories of the last 24 h: mine first, then people I know (unseen first)."""
    uin = require_uin(authorization)
    since = now_ms() - STORY_TTL_MS
    with conn() as c:
        people = [uin] + related_uins(c, uin)
        out = []
        for p in dict.fromkeys(people):
            rows = c.execute("SELECT * FROM stories WHERE uin=? AND ts>? ORDER BY ts", (p, since)).fetchall()
            if not rows:
                continue
            u = c.execute("SELECT * FROM users WHERE uin=?", (p,)).fetchone()
            if not u:
                continue
            items = [story_json(c, r, uin) for r in rows]
            out.append({"user": user_json(u), "stories": items, "all_seen": all(i["seen"] for i in items) or p == uin})
    mine = [x for x in out if x["user"]["uin"] == uin]
    others = sorted([x for x in out if x["user"]["uin"] != uin], key=lambda x: (x["all_seen"], -x["stories"][-1]["ts"]))
    return mine + others


@app.post("/api/stories/{sid}/view")
def view_story(sid: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        s = c.execute("SELECT uin FROM stories WHERE id=?", (sid,)).fetchone()
        if s and s["uin"] != uin:
            c.execute("INSERT OR IGNORE INTO story_views(story, uin, ts) VALUES(?,?,?)", (sid, uin, now_ms()))
    return {"ok": True}


class StoryCommentIn(BaseModel):
    text: str = Field(min_length=1, max_length=500)


def _story_for(c: sqlite3.Connection, sid: int, uin: int) -> sqlite3.Row:
    # publications stay in profiles, so they can be viewed / answered later too
    s = c.execute("SELECT * FROM stories WHERE id=?", (sid,)).fetchone()
    if not s:
        raise HTTPException(404, "Публикация не найдена")
    return s


@app.get("/api/stories/{sid}/comments")
def story_comments(sid: int, authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        _story_for(c, sid, uin)
        rows = c.execute(
            "SELECT k.id AS cid, k.text AS ctext, k.ts AS cts, u.* FROM story_comments k JOIN users u ON u.uin=k.uin"
            " WHERE k.story=? ORDER BY k.id LIMIT 300",
            (sid,),
        ).fetchall()
        return [{"id": r["cid"], "text": r["ctext"], "ts": r["cts"], "user": user_json(r, c=c)} for r in rows]


@app.post("/api/stories/{sid}/comments")
async def add_story_comment(sid: int, body: StoryCommentIn, authorization: Optional[str] = Header(None)) -> dict:
    """A comment under a story: everybody who sees the story sees it; the author gets a notification."""
    uin = require_uin(authorization)
    text = body.text.strip()
    if not text:
        raise HTTPException(400, "Пустой комментарий")
    with conn() as c:
        s = _story_for(c, sid, uin)
        cur = c.execute("INSERT INTO story_comments(story, uin, text, ts) VALUES(?,?,?,?)", (sid, uin, text[:500], now_ms()))
        me = c.execute("SELECT * FROM users WHERE uin=?", (uin,)).fetchone()
        item = {"id": cur.lastrowid, "text": text[:500], "ts": now_ms(), "user": user_json(me, c=c)}
        author = s["uin"]
    if author != uin:
        await manager.send(author, {"type": "stories"})
        if not manager.connected(author):
            push_data(author, {"type": "msg", "peer": uin, "title": me["name"], "body": "💬 Комментарий к публикации: " + text[:120]})
    return item


@app.post("/api/stories/{sid}/reply")
async def reply_story(sid: int, body: StoryCommentIn, authorization: Optional[str] = Header(None)) -> dict:
    """Answer to a publication: goes privately to its author's chat, with the publication quoted (like Telegram)."""
    uin = require_uin(authorization)
    text = body.text.strip()
    if not text:
        raise HTTPException(400, "Пустое сообщение")
    with conn() as c:
        s = _story_for(c, sid, uin)
        author = s["uin"]
        if author == uin:
            raise HTTPException(400, "Это ваша публикация")
        if is_blocked(c, author, uin):
            raise HTTPException(403, "Пользователь ограничил сообщения")
        for a, b in ((uin, author), (author, uin)):
            c.execute("INSERT OR IGNORE INTO contacts(owner, contact) VALUES(?,?)", (a, b))
        ttl = chat_ttl_of(c, uin, author, None)
        now = now_ms()
        cur = c.execute(
            "INSERT INTO messages(client_id, from_uin, to_uin, text, ts, kind, rev, story_id, story_att, expire_at)"
            " VALUES(?,?,?,?,?,'text',?,?,?,?)",
            ("st-" + secrets.token_hex(8), uin, author, text[:4000], now, next_rev(c), sid, s["att_id"],
             now + ttl * 1000 if ttl else 0),
        )
        row = c.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
        msg = msg_json(c, row)
        push_message(c, row, [author])
    await manager.send(author, {"type": "message", "message": msg})
    return msg


@app.delete("/api/stories/{sid}/comments/{cid}")
def delete_story_comment(sid: int, cid: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        s = _story_for(c, sid, uin)
        k = c.execute("SELECT uin FROM story_comments WHERE id=? AND story=?", (cid, sid)).fetchone()
        if not k or (k["uin"] != uin and s["uin"] != uin):
            raise HTTPException(403, "Нельзя удалить")
        c.execute("DELETE FROM story_comments WHERE id=?", (cid,))
    return {"ok": True}


class StoryReactIn(BaseModel):
    emoji: str


@app.post("/api/stories/{sid}/react")
async def react_story(sid: int, body: StoryReactIn, authorization: Optional[str] = Header(None)) -> dict:
    """A reaction to somebody's story: shown to the author in the viewers list (and as a message from me)."""
    uin = require_uin(authorization)
    emoji = body.emoji.strip()[:16]
    with conn() as c:
        s = c.execute("SELECT * FROM stories WHERE id=?", (sid,)).fetchone()
        if not s or s["uin"] == uin:
            raise HTTPException(404, "Публикация не найдена")
        c.execute(
            "INSERT INTO story_views(story, uin, ts, reaction) VALUES(?,?,?,?)"
            " ON CONFLICT(story, uin) DO UPDATE SET reaction=excluded.reaction",
            (sid, uin, now_ms(), emoji or None),
        )
        author = s["uin"]
    await manager.send(author, {"type": "stories"})
    return {"ok": True, "emoji": emoji}


@app.get("/api/stories/{sid}/viewers")
def story_viewers(sid: int, authorization: Optional[str] = Header(None)) -> list:
    uin = require_uin(authorization)
    with conn() as c:
        s = c.execute("SELECT uin FROM stories WHERE id=?", (sid,)).fetchone()
        if not s or s["uin"] != uin:
            raise HTTPException(404, "История не найдена")
        rows = c.execute(
            "SELECT u.*, v.ts AS vts, v.reaction AS vr FROM story_views v JOIN users u ON u.uin=v.uin"
            " WHERE v.story=? ORDER BY (v.reaction IS NOT NULL AND v.reaction<>'') DESC, v.ts DESC",
            (sid,),
        ).fetchall()
        return [dict(user_json(r), viewed=r["vts"], reaction=r["vr"] or "") for r in rows]


@app.delete("/api/stories/{sid}")
async def delete_story(sid: int, authorization: Optional[str] = Header(None)) -> dict:
    uin = require_uin(authorization)
    with conn() as c:
        c.execute("DELETE FROM stories WHERE id=? AND uin=?", (sid, uin))
        c.execute("DELETE FROM story_views WHERE story=?", (sid,))
        targets = related_uins(c, uin)
    await manager.send_many(targets + [uin], {"type": "stories"})
    return {"ok": True}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = Query("")) -> None:
    uin = uin_from_token(token)
    if uin is None:
        await ws.close(code=4401)
        return
    await ws.accept()
    manager.ws_tokens[id(ws)] = token
    CLUSTER.token_on(token)
    with conn() as c:
        c.execute("UPDATE tokens SET last_used=? WHERE token=?", (now_ms(), token))
    was_online = manager.online(uin)
    manager.add(uin, ws)
    if not was_online and manager.online(uin):
        await broadcast_presence(uin, True)
    await flush_calls(uin)
    asyncio.create_task(bot_welcome_if_needed(uin))
    try:
        while True:
            raw = await ws.receive_text()
            try:
                data = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(data, dict):
                continue
            kind = data.get("type")
            try:
                if kind in ("message", "typing", "read") and id(ws) in manager.idle:
                    # somebody who writes is clearly in the app, whatever the last "state" said
                    before = manager.online(uin)
                    manager.set_idle(uin, ws, False)
                    if not before:
                        await broadcast_presence(uin, True)
                if kind == "message":
                    await handle_message(uin, ws, data)
                elif kind == "typing":
                    await handle_typing(uin, data)
                elif kind == "read":
                    await handle_read(uin, data)
                elif kind == "edit":
                    await handle_edit(uin, data)
                elif kind == "delete":
                    await handle_delete(uin, data)
                elif kind == "react":
                    await handle_react(uin, data)
                elif kind == "played":
                    await handle_played(uin, data)
                elif kind == "call":
                    await handle_call(uin, data)
                elif kind == "state":  # app went to foreground / background
                    before = manager.online(uin)
                    manager.set_idle(uin, ws, not data.get("active"))
                    after = manager.online(uin)
                    if before != after:
                        await broadcast_presence(uin, after)
                elif kind == "ping":
                    await ws.send_text('{"type":"pong"}')
            except WebSocketDisconnect:
                raise
            except Exception as e:  # one bad frame must not kill the connection
                print(f"[ws] {uin}: {kind}: {e!r}", flush=True)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        before = manager.online(uin)
        manager.ws_tokens.pop(id(ws), None)
        if token not in manager.ws_tokens.values():
            CLUSTER.token_off(token)
        manager.remove(uin, ws)
        if before and not manager.online(uin):
            await broadcast_presence(uin, False)
