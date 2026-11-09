package com.icqnew.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icqnew.app.domain.ChatPreview
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.FoundMessage
import com.icqnew.app.domain.PublicChat
import com.icqnew.app.domain.SearchUsersUseCase
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val people: List<User> = emptyList(),
    val publicChats: List<PublicChat> = emptyList(),
    val messages: List<FoundMessage> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
)

/** Global search: my chats, people, public groups / channels and my messages. */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val search: SearchUsersUseCase,
    private val chats: ChatRepository,
    /** true = the screen was opened as "find channels" */
    private val channelsOnly: Boolean,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val myChats: StateFlow<List<ChatPreview>> =
        chats.observeChats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank() && !channelsOnly) {
                    _state.update {
                        it.copy(people = emptyList(), publicChats = emptyList(), messages = emptyList(),
                            loading = false, searched = false, error = null)
                    }
                    return@collectLatest
                }
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val people = async { if (channelsOnly || q.isBlank()) emptyList() else search(q) }
                    val pub = async { chats.searchChats(q).let { l -> if (channelsOnly) l.filter { it.isChannel } else l } }
                    val msgs = async { if (channelsOnly) emptyList() else chats.searchMessages(q) }
                    _state.update {
                        it.copy(people = people.await(), publicChats = pub.await(), messages = msgs.await(),
                            loading = false, searched = true)
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = e.userMessage()) }
                }
            }
        }
    }

    fun onQuery(v: String) {
        query.value = v
        _state.update { it.copy(query = v) }
    }

    /** Adds the user to contacts (so the chat has a peer) and opens the chat. */
    fun open(user: User, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                chats.addContact(user.uin)
                onReady(user.uin)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }
}
