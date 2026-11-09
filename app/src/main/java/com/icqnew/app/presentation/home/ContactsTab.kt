package com.icqnew.app.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icqnew.app.domain.ContactCard
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.IcqIcons
import com.icqnew.app.presentation.components.NameLine
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.components.plainClick
import com.icqnew.app.presentation.emoji.EmojiText
import com.icqnew.app.presentation.theme.IcqGreen

/** Little arrow that tells who added whom: ⇄ both, → only me, ← only him. */
@Composable
fun ContactArrow(card: ContactCard, modifier: Modifier = Modifier) {
    val (icon, tint, hint) = when {
        card.mutual -> Triple(Icons.Filled.SwapHoriz, IcqGreen, "взаимно")
        card.addedMe -> Triple(Icons.AutoMirrored.Filled.ArrowBack, MaterialTheme.colorScheme.tertiary, "добавил(а) вас")
        else -> Triple(Icons.AutoMirrored.Filled.ArrowForward, MaterialTheme.colorScheme.onSurfaceVariant, "вы добавили")
    }
    Icon(icon, hint, tint = tint, modifier = modifier.size(18.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(
    vm: HomeViewModel,
    fallback: List<User>,
    onOpen: (Long) -> Unit,
    onProfile: (Long) -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val cards by vm.contactCards.collectAsState()
    val incoming by vm.incoming.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<ContactCard?>(null) }
    LaunchedEffect(Unit) { vm.refreshContacts() }

    val list: List<ContactCard> = cards ?: fallback.map { ContactCard(it, "", null, iAdded = true, addedMe = false, addedAt = 0) }
    val online = list.count { it.user.online }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "actions") {
            ContactAction(Icons.Filled.PersonAdd, "Добавить контакт") { prefill = null; adding = true }
            ContactAction(Icons.Filled.Search, "Найти по ID, номеру или имени", onSearch)
            ContactAction(Icons.Filled.GroupAdd, "Новая группа", onNewGroup)
            ContactAction(IcqIcons.Contacts, "Синхронизировать контакты", onSync)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (incoming.isNotEmpty()) {
            item(key = "incoming_title") {
                Row(
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Добавили вас: ${incoming.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            items(incoming, key = { "in" + it.user.uin }) { c ->
                ContactRow(
                    c, modifier = Modifier.animateItem(),
                    onOpen = { onOpen(c.user.uin) }, onProfile = { onProfile(c.user.uin) },
                    trailing = {
                        FilledTonalButton(
                            onClick = { prefill = c; adding = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) { Text("Добавить", fontSize = 13.sp) }
                    },
                )
            }
            item(key = "incoming_div") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        }
        item(key = "count") {
            Text(
                if (list.isEmpty()) "Здесь появятся ваши контакты. Добавьте друга по номеру телефона или ID."
                else "Контакты: ${list.size} · в сети: $online",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            )
        }
        items(list.sortedWith(compareByDescending<ContactCard> { it.user.online }.thenBy { it.user.name.lowercase() }), key = { it.user.uin }) { c ->
            ContactRow(
                c, modifier = Modifier.animateItem(),
                onOpen = { onOpen(c.user.uin) }, onProfile = { onProfile(c.user.uin) },
                trailing = { ContactArrow(c) },
            )
        }
        item(key = "bottom") { Spacer(Modifier.height(88.dp)) }
    }

    if (adding) {
        AddContactDialog(
            initial = prefill,
            onDismiss = { adding = false },
            onAdd = { phone, query, name, share, done ->
                vm.addContact(phone, query, name, share) { ok -> if (ok) adding = false; done() }
            },
        )
    }
}

@Composable
private fun ContactRow(
    c: ContactCard,
    modifier: Modifier,
    onOpen: () -> Unit,
    onProfile: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val u = c.user
    ListItem(
        modifier = modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(Modifier.plainClick(onProfile)) { Avatar(u.name, u.uin, u.avatarUrl, u.online, 48.dp) }
        },
        headlineContent = { NameLine(u.name, verified = u.verified, bot = u.uin == com.icqnew.app.domain.BOT_UIN) },
        supportingContent = {
            val line = when {
                u.about.isNotBlank() && u.uin != com.icqnew.app.domain.BOT_UIN -> u.about
                c.phone.isNotBlank() && !u.online -> c.phone
                else -> formatPresence(u.online, u.lastSeen, u.uin)
            }
            EmojiText(
                line, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (u.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = trailing,
    )
}

@Composable
private fun ContactAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        },
        headlineContent = { Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) },
    )
}

/**
 * "Add contact": by phone number or by ID / @username, with my own name for the person
 * and a switch to show (or not) my phone number to him.
 */
@Composable
fun AddContactDialog(
    initial: ContactCard?,
    onDismiss: () -> Unit,
    onAdd: (phone: String?, query: String?, name: String?, share: Boolean, done: () -> Unit) -> Unit,
) {
    var byPhone by remember { mutableStateOf(initial == null) }
    var phone by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(initial?.user?.uin?.toString() ?: "") }
    var name by remember { mutableStateOf(initial?.user?.name ?: "") }
    var share by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial != null) "Добавить в ответ" else "Новый контакт") },
        text = {
            Column {
                if (initial == null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
                    ) {
                        listOf(true to "По номеру", false to "По ID / @имени").forEach { (v, label) ->
                            val sel = byPhone == v
                            val a by animateFloatAsState(if (sel) 1f else 0f, label = "seg")
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f * a))
                                    .clickable { byPhone = v }.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (byPhone) {
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch in "+ -()" }.take(20) },
                        label = { Text("Номер телефона") }, placeholder = { Text("+993 61 234567") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (initial == null) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.trim().take(40) },
                        label = { Text("ID или @имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(64) },
                    label = { Text("Имя в контактах (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { share = !share }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = share, onCheckedChange = { share = it })
                    Column {
                        Text("Показать мой номер")
                        Text(
                            if (share) "Контакт увидит ваш номер телефона" else "Номер останется скрытым",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (if (byPhone) phone.count { it.isDigit() } >= 8 else query.isNotBlank()),
                onClick = {
                    busy = true
                    onAdd(if (byPhone) phone else null, if (byPhone) null else query, name, share) { busy = false }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
