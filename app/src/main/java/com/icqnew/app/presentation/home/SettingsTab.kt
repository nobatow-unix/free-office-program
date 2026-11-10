package com.icqnew.app.presentation.home

import androidx.compose.ui.draw.scale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Folder
import com.icqnew.app.presentation.appContainer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.domain.ConnectionState
import com.icqnew.app.domain.ThemeMode
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.IcqLogo
import com.icqnew.app.presentation.emoji.EmojiText
import com.icqnew.app.presentation.theme.Accents

@Composable
fun SettingsTab(
    vm: HomeViewModel,
    onOpenChat: (Long) -> Unit,
    onPickAvatar: () -> Unit,
    onLoggedOut: () -> Unit,
    onOpenProfile: (Long) -> Unit,
) {
    val session by vm.session.collectAsStateWithLifecycle()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    var privacyPick by remember { mutableStateOf<String?>(null) }
    privacyPick?.let { what ->
        val current = if (what == "avatar") privacy.avatarPrivacy else privacy.phonePrivacy
        AlertDialog(
            onDismissRequest = { privacyPick = null },
            title = { Text(if (what == "avatar") "Кто видит фото профиля" else "Кто видит мой номер") },
            text = {
                Column {
                    listOf("all", "contacts", "nobody").forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                vm.setPrivacy(if (what == "avatar") privacy.copy(avatarPrivacy = mode) else privacy.copy(phonePrivacy = mode))
                                privacyPick = null
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == mode, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(privacyLabel(mode))
                        }
                    }
                    if (what == "phone") Text(
                        "Номер всегда видят те, кому вы сами показали его при добавлении в контакты.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { privacyPick = null }) { Text("Закрыть") } },
        )
    }
    val app by vm.app.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editProfile by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var pickWallpaper by remember { mutableStateOf(false) }
    var editUsername by remember { mutableStateOf(false) }
    var pickSound by remember { mutableStateOf(false) }
    var pickSwipe by remember { mutableStateOf(false) }
    var pickTtl by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showIcons by remember { mutableStateOf(false) }
    val myProfile by vm.myProfile.collectAsStateWithLifecycle()
    val premium = myProfile?.premium == true
    LaunchedEffect(Unit) { vm.loadPremium() }
    val defaultTtl by vm.defaultTtl.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadDefaultTtl() }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadSessions() }
    var nameInput by remember { mutableStateOf("") }
    var aboutInput by remember { mutableStateOf("") }

    val name = session?.name ?: ""
    val uin = session?.uin ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- me (compact; the full profile is on the "Профиль" tab) ----
        Row(
            Modifier.fillMaxWidth().clickable { nameInput = name; aboutInput = session?.about.orEmpty(); editProfile = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onPickAvatar)) {
                Avatar(name, uin, session?.avatarUrl, false, 58.dp, storyRing = false)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                com.icqnew.app.presentation.components.NameLine(name, verified = session?.verified == true, fontSize = 19.sp)
                Text(
                    session?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Задайте имя пользователя",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
            }
            Icon(Icons.Filled.Edit, "Изменить", tint = MaterialTheme.colorScheme.primary)
        }

        // ---- privacy ----
        Section("Конфиденциальность") {
            Line(
                Icons.Filled.PhoneAndroid, "Кто видит мой номер", privacyLabel(privacy.phonePrivacy), arrow = true,
            ) { privacyPick = "phone" }
            Line(
                Icons.Filled.CameraAlt, "Кто видит фото профиля", privacyLabel(privacy.avatarPrivacy), arrow = true,
            ) { privacyPick = "avatar" }
            Toggle(
                Icons.Filled.Visibility, "Показывать время посещения",
                "Если выключить, другие увидят «был(а) недавно»", privacy.showOnline,
            ) { vm.setPrivacy(privacy.copy(showOnline = it)) }
            Toggle(
                Icons.Filled.Search, "Находить меня по номеру и ID",
                "Иначе вас не найдут через поиск", privacy.searchable,
            ) { vm.setPrivacy(privacy.copy(searchable = it)) }
            Toggle(Icons.Filled.DoneAll, "Отчёты о прочтении", "Собеседник видит, что вы прочитали", privacy.sendReadReceipts) {
                vm.setPrivacy(privacy.copy(sendReadReceipts = it))
            }
            Toggle(Icons.Filled.Edit, "Показывать «печатает…»", "Собеседник видит, когда вы пишете", privacy.sendTyping) {
                vm.setPrivacy(privacy.copy(sendTyping = it))
            }
            Toggle(Icons.Filled.Lock, "Запретить скриншоты", "Экран нельзя снять и показать в недавних", privacy.blockScreenshots) {
                vm.setPrivacy(privacy.copy(blockScreenshots = it))
            }
            Line(Icons.Filled.Block, "Заблокированные", if (blocked.isEmpty()) "Нет" else "${blocked.size}", arrow = true) {
                showBlocked = true
            }
        }

        Section("Устройства") {
            Line(
                Icons.Filled.Devices, "Активные сеансы",
                if (sessions.isEmpty()) null else "${sessions.size}", arrow = true,
            ) { vm.loadSessions(); showDevices = true }
            Line(Icons.Filled.QrCodeScanner, "Веб-версия", "вход по QR-коду в профиле")
        }

        // ---- notifications ----
        Section("Уведомления и звуки") {
            Line(Icons.Filled.MusicNote, "Звук сообщения", soundLabel(app.soundChoice), arrow = true) { pickSound = true }
            Toggle(Icons.Filled.Vibration, "Вибрация", "Короткая вибрация при новом сообщении", app.vibrate, vm::setVibrate)
            Toggle(
                Icons.Filled.Notifications, "Показывать текст", "Текст сообщения в уведомлении",
                app.notifyPreview, vm::setNotifyPreview,
            )
        }

        Section("Папки с чатами") {
            Line(
                Icons.Filled.Folder, "Папки",
                if (app.folders.isEmpty()) "нет" else "${app.folders.size}", arrow = true,
            ) { showFolders = true }
        }

        // ---- chats ----
        Section("Настройки чатов") {
            Line(
                Icons.Filled.Timer, "Автоудаление сообщений",
                if (defaultTtl < 0) "…" else ttlName(defaultTtl), arrow = true,
            ) { pickTtl = true }
            Line(Icons.Filled.SwipeLeft, "Свайп влево по чату", swipeLabel(app.swipeAction), arrow = true) { pickSwipe = true }
            Line(Icons.Filled.Wallpaper, "Фон чатов", null, arrow = true) { pickWallpaper = true }
            Line(Icons.Filled.FormatSize, "Размер текста", "${(app.fontScale * 100).toInt()}%")
            Slider(
                value = app.fontScale,
                onValueChange = { vm.setFontScale((it * 20).toInt() / 20f) },
                valueRange = 0.85f..1.3f,
                steps = 8,
                modifier = Modifier.padding(start = 56.dp, end = 20.dp),
            )
        }

        // ---- appearance ----
        Section("Оформление") {
            Line(Icons.Filled.DarkMode, "Тема", null)
            Row(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(ThemeMode.SYSTEM to "Авто", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная")
                    .forEach { (mode, label) ->
                        FilterChip(
                            selected = app.theme == mode, onClick = { vm.setTheme(mode) }, label = { Text(label) },
                        )
                    }
            }
            Line(Icons.Filled.Palette, "Цвет оформления", Accents.getOrNull(app.accent)?.first + if (!premium) " · 🔒 больше с Premium" else "")
            Row(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Accents.forEachIndexed { i, (_, color) ->
                    val locked = i > 2 && !premium
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(color).clickable {
                            if (locked) Toast.makeText(context, "Этот цвет — с ICQ Premium ⭐", Toast.LENGTH_SHORT).show()
                            else vm.setAccent(i)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (app.accent == i) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = Color.White)
                        else if (locked) Icon(Icons.Filled.Lock, null, Modifier.size(15.dp), tint = Color.White)
                    }
                }
            }
            Line(Icons.Filled.Apps, "Значок приложения", if (premium) null else "🔒 Premium", arrow = true) { showIcons = true }
        }

        Section("Язык") {
            Line(Icons.Filled.Language, "Язык приложения", languageName(app.language), arrow = true) { showLanguage = true }
        }

        // ---- the rest ----
        Section("Прочие настройки") {
            Line(Icons.Filled.Storage, "Очистить кэш", "Скачанные фото и файлы (переписка останется)", arrow = true) {
                vm.clearCache(java.io.File(context.cacheDir, "media"))
            }
            val updater = context.appContainer().updater
            Line(Icons.Filled.SystemUpdate, "Проверить обновления", "Версия " + updater.currentName, arrow = true) {
                updater.check()
                Toast.makeText(context, "Если есть новая версия, она появится внизу экрана", Toast.LENGTH_SHORT).show()
            }
            Line(Icons.Filled.Info, "О приложении", "ICQ New " + updater.currentName, arrow = true) { showAbout = true }
        }

        Section(null) {
            Line(Icons.Filled.SupportAgent, "Поддержка", "вопросы, пополнение баланса", arrow = true) {
                onOpenChat(supportPeer(vm.premium.value))
            }
            Line(Icons.Filled.Share, "Пригласить друзей", null, arrow = true) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    "Пиши мне в ICQ New! " +
                        (session?.username?.takeIf { it.isNotBlank() }?.let { com.icqnew.app.presentation.IcqLinks.user(it) } ?: "") +
                        "\nСкачать: ${com.icqnew.app.presentation.IcqLinks.SITE}",
                )
                context.startActivity(android.content.Intent.createChooser(send, "Пригласить"))
            }
        }
        Section(null) {
            Line(Icons.AutoMirrored.Filled.ExitToApp, "Выйти из аккаунта", null, tint = MaterialTheme.colorScheme.error) {
                vm.logout(onLoggedOut)
            }
            Line(Icons.Filled.DeleteForever, "Удалить аккаунт", null, tint = MaterialTheme.colorScheme.error) {
                showDelete = true
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editProfile) {
        AlertDialog(
            onDismissRequest = { editProfile = false },
            title = { Text("Профиль") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput, onValueChange = { nameInput = it.take(40) }, singleLine = true,
                        label = { Text("Имя") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aboutInput, onValueChange = { aboutInput = it.take(140) }, maxLines = 3,
                        label = { Text("О себе / статус") },
                        supportingText = { Text("${aboutInput.length}/140") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = nameInput.isNotBlank(), onClick = { vm.updateProfile(nameInput, aboutInput); editProfile = false }) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { editProfile = false }) { Text("Отмена") } },
        )
    }
    if (pickSound) {
        val player = context.appContainer().sound
        ChoiceDialog(
            title = "Звук сообщения",
            options = listOf("icq" to "ICQ «О-оу!»", "classic" to "Классический", "system" to "Звук телефона", "none" to "Без звука"),
            selected = app.soundChoice,
            onPick = { v -> vm.setSoundChoice(v); player.preview(v) },
            onDismiss = { pickSound = false },
        )
    }
    if (pickTtl) {
        ChoiceDialog(
            title = "Автоудаление во всех чатах",
            options = listOf(0, 86400, 2 * 86400, 7 * 86400, 30 * 86400).map { it.toString() to ttlName(it) },
            selected = defaultTtl.toString(),
            onPick = { v -> vm.setDefaultTtl(v.toInt()); pickTtl = false },
            onDismiss = { pickTtl = false },
        )
    }
    if (pickSwipe) {
        ChoiceDialog(
            title = "Свайп влево по чату",
            options = listOf(
                "delete" to "Удалить", "archive" to "В архив", "pin" to "Закрепить",
                "mute" to "Без звука", "read" to "Прочитано",
            ),
            selected = app.swipeAction,
            onPick = { v -> vm.setSwipeAction(v); pickSwipe = false },
            onDismiss = { pickSwipe = false },
        )
    }
    if (showFolders) FoldersDialog(vm, app.folders, premium) { showFolders = false }
    if (showLanguage) LanguageDialog(app.language, onPick = { vm.setLanguage(it); showLanguage = false }) { showLanguage = false }
    if (showIcons) AppIconDialog(premium) { showIcons = false }
    if (pickWallpaper) {
        com.icqnew.app.presentation.components.WallpaperPicker(
            premium = premium,
            current = app.wallpaper.ifEmpty { "pattern" },
            allowReset = false,
            onPick = { vm.setWallpaper(it) },
            onDismiss = { pickWallpaper = false },
        )
    }
    if (editUsername) {
        var value by remember { mutableStateOf(session?.username.orEmpty()) }
        var hint by remember { mutableStateOf<String?>(null) }
        var free by remember { mutableStateOf(false) }
        LaunchedEffect(value) {
            val v = value.trim().removePrefix("@")
            if (v.isEmpty() || v == session?.username) {
                hint = null; free = v.isEmpty() || v == session?.username
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(400)
            val r = vm.checkUsername(v)
            free = r == null
            hint = r ?: "✔ Имя свободно"
        }
        AlertDialog(
            onDismissRequest = { editUsername = false },
            title = { Text("Имя пользователя") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it.take(33).filter { c -> c.isLetterOrDigit() || c == '_' || c == '@' } },
                        singleLine = true, prefix = { Text("@") }, label = { Text("username") },
                    )
                    hint?.let {
                        Text(it, fontSize = 13.sp, color = if (free) Color(0xFF00C853) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "По этому имени вас смогут найти через поиск (@имя), не зная номера. " +
                            "4–32 символа: латиница, цифры и _. Оставьте пустым, чтобы удалить.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = free || value.isBlank(), onClick = { vm.setUsername(value) { editUsername = false } }) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { editUsername = false }) { Text("Отмена") } },
        )
    }
    if (showDevices) {
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("Устройства") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (sessions.isEmpty()) Text("Загрузка…")
                    sessions.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.device, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        d.current -> "это устройство"
                                        d.online -> "в сети"
                                        else -> "активность: " + com.icqnew.app.presentation.components.formatListTime(d.lastUsed)
                                    },
                                    fontSize = 13.sp,
                                    color = if (d.current || d.online) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!d.current) {
                                TextButton(onClick = { vm.endSession(d.id) }) {
                                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (sessions.count { !it.current } > 0) {
                        TextButton(onClick = { vm.endSession("others") }) {
                            Text("Завершить все другие сеансы", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Закрыть") } },
        )
    }
    if (showBlocked) {
        AlertDialog(
            onDismissRequest = { showBlocked = false },
            title = { Text("Заблокированные") },
            text = {
                if (blocked.isEmpty()) Text("Список пуст")
                else Column {
                    blocked.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Box(Modifier.clip(CircleShape).clickable { showBlocked = false; onOpenProfile(u.uin) }) {
                                Avatar(u.name, u.uin, u.avatarUrl, false, 40.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            EmojiText(u.name, Modifier.weight(1f), maxLines = 1)
                            TextButton(onClick = { vm.unblock(u.uin) }) { Text("Разблокировать") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBlocked = false }) { Text("Закрыть") } },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { IcqLogo(64.dp) },
            title = { Text("ICQ New " + context.appContainer().updater.currentName) },
            text = {
                Text(
                    "Мессенджер: чаты, группы, каналы, звонки.\n\nСмайлы: Microsoft Fluent Emoji (лицензия MIT).",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Профиль, переписка и файлы будут удалены с сервера безвозвратно.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.deleteAccount(onLoggedOut) }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(
            title,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 26.dp, top = 12.dp, bottom = 4.dp),
        )
    } else Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
private fun Line(
    icon: ImageVector,
    title: String,
    value: String?,
    arrow: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), color = tint ?: MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        if (arrow) Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.scale(0.8f))
    }
}


private fun privacyLabel(mode: String): String = when (mode) {
    "contacts" -> "Мои контакты"
    "nobody" -> "Никто"
    else -> "Все"
}


private fun soundLabel(v: String) = when (v) {
    "classic" -> "Классический"
    "system" -> "Звук телефона"
    "none" -> "Без звука"
    else -> "ICQ «О-оу!»"
}

fun swipeLabel(v: String) = when (v) {
    "archive" -> "В архив"
    "pin" -> "Закрепить"
    "mute" -> "Без звука"
    "read" -> "Прочитано"
    else -> "Удалить"
}

private fun ttlName(sec: Int): String = when (sec) {
    0 -> "Выключено"
    3600 -> "1 час"
    86400 -> "1 день"
    2 * 86400 -> "2 дня"
    7 * 86400 -> "1 неделя"
    30 * 86400 -> "1 месяц"
    else -> "$sec с"
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (k, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(k) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == k, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
