package com.icqnew.app.presentation.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.domain.groupPeer
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.chat.subscribers
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.formatListTime
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.components.membersText
import com.icqnew.app.presentation.emoji.EmojiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenPublic: (Long) -> Unit,
    channelsOnly: Boolean = false,
) {
    val vm = appViewModel(key = "search$channelsOnly") { c -> SearchViewModel(c.searchUsers, c.chatRepository, channelsOnly) }
    val s by vm.state.collectAsStateWithLifecycle()
    val myChats by vm.myChats.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = s.query.trim()
    val localChats = if (q.isEmpty() || channelsOnly) emptyList()
    else myChats.filter { it.peer.name.contains(q, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = s.query,
                        onValueChange = vm::onQuery,
                        singleLine = true,
                        placeholder = { Text(if (channelsOnly) "Название канала" else "Люди, группы, каналы, сообщения") },
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        trailingIcon = {
                            if (s.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) {
                                Icon(Icons.Filled.Close, "Очистить")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (localChats.isNotEmpty()) {
                    item { Header("Ваши чаты") }
                    items(localChats, key = { "c" + it.peer.uin }) { c ->
                        Row2(
                            avatar = {
                                Avatar(c.peer.name, c.peer.uin, c.peer.avatarUrl, c.peer.online && !c.peer.isGroup, 46.dp,
                                    isGroup = c.peer.isGroup, isChannel = c.peer.isChannel)
                            },
                            title = c.peer.name,
                            subtitle = c.lastText,
                            verified = c.peer.verified,
                            onClick = { onOpenChat(c.peer.uin) },
                        )
                    }
                }
                if (s.people.isNotEmpty()) {
                    item { Header("Люди") }
                    items(s.people, key = { "u" + it.uin }) { u ->
                        Row2(
                            avatar = { Avatar(u.name, u.uin, u.avatarUrl, u.online, 46.dp) },
                            title = u.name,
                            subtitle = (u.username?.let { "@$it · " } ?: "") + "ID ${u.uin} · ${formatPresence(u.online, u.lastSeen, u.uin)}",
                            verified = u.verified,
                            onClick = { vm.open(u, onOpenChat) },
                        )
                    }
                }
                if (s.publicChats.isNotEmpty()) {
                    item { Header(if (channelsOnly && q.isEmpty()) "Популярные каналы" else "Группы и каналы") }
                    items(s.publicChats, key = { "g" + it.id }) { g ->
                        Row2(
                            avatar = { Avatar(g.name, groupPeer(g.id), g.avatarUrl, false, 46.dp, isGroup = true, isChannel = g.isChannel) },
                            title = g.name,
                            subtitle = (if (g.isChannel) subscribers(g.count) else membersText(g.count)) +
                                (if (g.about.isNotBlank()) " · " + g.about else ""),
                            trailing = if (g.member) "вы участник" else null,
                            verified = g.verified,
                            onClick = { if (g.member) onOpenChat(groupPeer(g.id)) else onOpenPublic(g.id) },
                        )
                    }
                }
                if (s.messages.isNotEmpty()) {
                    item { Header("Сообщения") }
                    items(s.messages, key = { "m" + it.peer + "_" + it.ts }) { m ->
                        Row2(
                            avatar = { Avatar(m.chatName, m.peer, null, false, 46.dp, isGroup = m.peer < 0) },
                            title = m.chatName,
                            subtitle = m.text,
                            trailing = formatListTime(m.ts),
                            onClick = { onOpenChat(m.peer) },
                        )
                    }
                }
                if (s.searched && !s.loading && localChats.isEmpty() && s.people.isEmpty() &&
                    s.publicChats.isEmpty() && s.messages.isEmpty()
                ) {
                    item {
                        Text(
                            "Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (!s.searched && q.isEmpty() && !channelsOnly) {
                    item {
                        Text(
                            "Ищите людей по имени, ID или номеру телефона, открытые группы и каналы — по названию, а также свои сообщения.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row2(
    avatar: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: String? = null,
    verified: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = avatar,
        headlineContent = { com.icqnew.app.presentation.components.NameLine(title, verified = verified) },
        supportingContent = {
            EmojiText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = trailing?.let { t -> { Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}
