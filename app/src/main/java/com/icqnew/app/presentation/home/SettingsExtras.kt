package com.icqnew.app.presentation.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.R
import com.icqnew.app.domain.ChatFolder
import com.icqnew.app.presentation.components.AppIcons
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.emoji.EmojiText

val LANGUAGES = listOf("ru" to "Русский", "tk" to "Türkmençe", "en" to "English")

fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: "Русский"

/** Language of the app. Russian is complete; Turkmen / English are being translated (marked so). */
@Composable
fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Язык приложения") },
        text = {
            Column {
                LANGUAGES.forEach { (code, name) ->
                    val ready = code == "ru"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            if (ready) onPick(code)
                            else Toast.makeText(context, "$name — перевод скоро будет готов", Toast.LENGTH_SHORT).show()
                        }.padding(vertical = 12.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 16.sp)
                            if (!ready) Text("скоро", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (code == current) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

/** Home-screen icon (the first one is free, the others need Premium). */
@Composable
fun AppIconDialog(premium: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Значок приложения") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AppIcons.all.forEachIndexed { i, ic ->
                        val locked = i > 0 && !premium
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                if (locked) Toast.makeText(context, "Этот значок — с ICQ Premium ⭐", Toast.LENGTH_SHORT).show()
                                else { AppIcons.set(context, ic.alias); current = ic.alias }
                            }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(ic.background)
                                    .border(
                                        if (current == ic.alias) 3.dp else 1.dp,
                                        if (current == ic.alias) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(16.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.foundation.Image(painterResource(R.drawable.ic_logo), null, Modifier.size(38.dp))
                                if (locked) Box(
                                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).clip(RoundedCornerShape(9.dp))
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
                            }
                            Text(ic.title, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text(
                    "Значок на главном экране сменится через несколько секунд.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

/** Chat folders: create / rename / delete, choose the chats. Free: 2, Premium: 10. */
@Composable
fun FoldersDialog(vm: HomeViewModel, folders: List<ChatFolder>, premium: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Pair<Int, ChatFolder>?>(null) } // index (-1 = new), folder
    val limit = if (premium) ChatFolder.PREMIUM_LIMIT else ChatFolder.FREE_LIMIT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Папки с чатами") },
        text = {
            Column {
                Text(
                    "Папки появятся вкладками над списком «Личные». Без Premium — ${ChatFolder.FREE_LIMIT}, с Premium — ${ChatFolder.PREMIUM_LIMIT}.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                folders.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold)
                            Text("${f.peers.size} чат(ов)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = i to f }) { Icon(Icons.Filled.Edit, "Изменить") }
                        IconButton(onClick = { vm.setFolders(folders.filterIndexed { j, _ -> j != i }) }) {
                            Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                TextButton(onClick = {
                    if (folders.size >= limit) Toast.makeText(
                        context, if (premium) "Максимум ${ChatFolder.PREMIUM_LIMIT} папок" else "Больше папок — с ICQ Premium ⭐", Toast.LENGTH_SHORT,
                    ).show()
                    else editing = -1 to ChatFolder("", emptySet())
                }) {
                    Icon(if (folders.size >= limit && !premium) Icons.Filled.Lock else Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp)); Text("Новая папка")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
    editing?.let { (index, folder) ->
        FolderEditor(vm, folder, onDismiss = { editing = null }) { saved ->
            vm.setFolders(if (index < 0) folders + saved else folders.mapIndexed { j, f -> if (j == index) saved else f })
            editing = null
        }
    }
}

@Composable
private fun FolderEditor(vm: HomeViewModel, folder: ChatFolder, onDismiss: () -> Unit, onSave: (ChatFolder) -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(folder.name) }
    var peers by remember { mutableStateOf(folder.peers) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder.name.isEmpty()) "Новая папка" else "Папка") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                Text("Чаты в папке", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(chats.orEmpty(), key = { it.peer.uin }) { c ->
                        val on = c.peer.uin in peers
                        Row(
                            Modifier.fillMaxWidth().clickable { peers = if (on) peers - c.peer.uin else peers + c.peer.uin }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.peer.name, c.peer.uin, c.peer.avatarUrl, false, 34.dp, isGroup = c.peer.isGroup, isChannel = c.peer.isChannel)
                            Spacer(Modifier.width(10.dp))
                            EmojiText(c.peer.name, maxLines = 1, modifier = Modifier.weight(1f))
                            Checkbox(on, { peers = if (it) peers + c.peer.uin else peers - c.peer.uin })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(ChatFolder(name.trim(), peers)) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
