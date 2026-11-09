package com.icqnew.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.icqnew.app.data.notify.ConnectionService
import com.icqnew.app.domain.isGroupPeer
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.auth.LoginScreen
import com.icqnew.app.presentation.chat.ChatScreen
import com.icqnew.app.presentation.group.GroupInfoScreen
import com.icqnew.app.presentation.group.JoinScreen
import com.icqnew.app.presentation.group.GroupSettingsScreen
import com.icqnew.app.presentation.group.NewGroupScreen
import com.icqnew.app.presentation.home.HomeScreen
import com.icqnew.app.presentation.profile.ProfileScreen
import com.icqnew.app.presentation.search.SearchScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val CHAT = "chat/{uin}"
    const val PROFILE = "profile/{uin}"
    const val GROUP = "group/{peer}"
    const val NEW_GROUP = "newgroup/{channel}"
    const val JOIN = "join/{gid}"
    const val GROUP_SETTINGS = "groupsettings/{peer}"
    fun groupSettings(peer: Long) = "groupsettings/$peer"
    const val FIND_CHANNELS = "findchannels"
    fun newGroup(channel: Boolean) = "newgroup/$channel"
    fun join(gid: Long) = "join/$gid"
    fun chat(uin: Long) = "chat/$uin"
    fun profile(uin: Long) = "profile/$uin"
    fun group(peer: Long) = "group/$peer"
}

@Composable
fun AppNav(
    startDestination: String,
    openChatRequest: MutableStateFlow<Long?>,
    openLinkRequest: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val openPeer by openChatRequest.collectAsStateWithLifecycle()
    val openLink by openLinkRequest.collectAsStateWithLifecycle()

    // https://icq-new.online/@name, /g/123, icqnew://open?u=… -> profile / channel page
    LaunchedEffect(openLink) {
        val link = openLink ?: return@LaunchedEffect
        openLinkRequest.value = null
        if (startDestination != Routes.HOME) return@LaunchedEffect
        when (val t = com.icqnew.app.presentation.IcqLinks.parse(link)) {
            is com.icqnew.app.presentation.IcqLinks.Target.User -> nav.navigate(Routes.profile(t.uin))
            is com.icqnew.app.presentation.IcqLinks.Target.Group -> nav.navigate(Routes.join(t.gid))
            is com.icqnew.app.presentation.IcqLinks.Target.Name -> {
                val peer = context.appContainer().chatRepository.resolveMention(t.username)
                when {
                    peer == null -> android.widget.Toast.makeText(context, "@${t.username} не найден", android.widget.Toast.LENGTH_SHORT).show()
                    peer < 0 -> nav.navigate(Routes.join(-peer))
                    else -> nav.navigate(Routes.profile(peer))
                }
            }
            null -> Unit
        }
    }

    // Notification tap -> open that chat on top of the chat list.
    LaunchedEffect(openPeer) {
        val peer = openPeer ?: return@LaunchedEffect
        if (startDestination == Routes.HOME) {
            nav.navigate(Routes.chat(peer)) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
        }
        openChatRequest.value = null
    }

    fun openInfo(peer: Long) {
        nav.navigate(if (isGroupPeer(peer)) Routes.group(peer) else Routes.profile(peer))
    }

    NavHost(
        navController = nav,
        startDestination = startDestination,
        // iOS-like push: the new screen slides in from the right, the old one drifts left and dims
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { it } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { -it / 4 } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280), targetAlpha = 0.6f)
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { -it / 4 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280), initialAlpha = 0.6f)
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { it } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280))
        },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(onAuthenticated = {
                ConnectionService.start(context)
                nav.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { nav.navigate(Routes.chat(it)) },
                onOpenProfile = { openInfo(it) },
                onSearch = { nav.navigate(Routes.SEARCH) },
                onNewGroup = { nav.navigate(Routes.newGroup(false)) },
                onNewChannel = { nav.navigate(Routes.newGroup(true)) },
                onFindChannels = { nav.navigate(Routes.FIND_CHANNELS) },
                onLoggedOut = {
                    ConnectionService.stop(context)
                    nav.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { uin ->
                    nav.navigate(Routes.chat(uin)) { popUpTo(Routes.SEARCH) { inclusive = true } }
                },
                onOpenPublic = { gid -> nav.navigate(Routes.join(gid)) },
            )
        }
        composable(Routes.FIND_CHANNELS) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { uin ->
                    nav.navigate(Routes.chat(uin)) { popUpTo(Routes.FIND_CHANNELS) { inclusive = true } }
                },
                onOpenPublic = { gid -> nav.navigate(Routes.join(gid)) },
                channelsOnly = true,
            )
        }
        composable(
            route = Routes.JOIN,
            arguments = listOf(navArgument("gid") { type = NavType.LongType }),
        ) { entry ->
            JoinScreen(
                gid = entry.arguments?.getLong("gid") ?: 0L,
                onBack = { nav.popBackStack() },
                onJoined = { peer -> nav.navigate(Routes.chat(peer)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(
            route = Routes.NEW_GROUP,
            arguments = listOf(navArgument("channel") { type = NavType.BoolType }),
        ) { entry ->
            NewGroupScreen(
                channel = entry.arguments?.getBoolean("channel") ?: false,
                onBack = { nav.popBackStack() },
                onCreated = { peer ->
                    nav.navigate(Routes.chat(peer)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("uin") { type = NavType.LongType }),
        ) { entry ->
            val peer = entry.arguments?.getLong("uin") ?: 0L
            ChatScreen(
                uin = peer,
                onBack = { nav.popBackStack() },
                onOpenInfo = { openInfo(peer) },
                onOpenProfile = { uin -> nav.navigate(Routes.profile(uin)) },
                onOpenChat = { other ->
                    // groups / channels go through their page (join if needed)
                    if (other < 0) nav.navigate(Routes.join(-other))
                    else nav.navigate(Routes.chat(other)) { popUpTo(Routes.HOME) }
                },
                onOpenLink = { link -> openLinkRequest.value = link },
            )
        }
        composable(
            route = Routes.PROFILE,
            arguments = listOf(navArgument("uin") { type = NavType.LongType }),
        ) { entry ->
            val uin = entry.arguments?.getLong("uin") ?: 0L
            ProfileScreen(
                uin = uin,
                onBack = { nav.popBackStack() },
                onOpenChat = {
                    nav.navigate(Routes.chat(uin)) { popUpTo(Routes.HOME) }
                },
                onOpenChannel = { gid -> nav.navigate(Routes.join(gid)) },
            )
        }
        composable(
            route = Routes.GROUP,
            arguments = listOf(navArgument("peer") { type = NavType.LongType }),
        ) { entry ->
            val peer = entry.arguments?.getLong("peer") ?: 0L
            GroupInfoScreen(
                peer = peer,
                onBack = { nav.popBackStack() },
                onOpenProfile = { uin -> nav.navigate(Routes.profile(uin)) },
                onLeft = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenSettings = { nav.navigate(Routes.groupSettings(peer)) },
            )
        }
        composable(
            route = Routes.GROUP_SETTINGS,
            arguments = listOf(navArgument("peer") { type = NavType.LongType }),
        ) { entry ->
            GroupSettingsScreen(peer = entry.arguments?.getLong("peer") ?: 0L, onBack = { nav.popBackStack() })
        }
    }
}
