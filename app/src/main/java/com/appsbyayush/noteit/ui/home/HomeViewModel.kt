package com.appsbyayush.noteit.ui.home

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appsbyayush.noteit.R
import com.appsbyayush.noteit.models.AppSettings
import com.appsbyayush.noteit.models.Note
import com.appsbyayush.noteit.models.SortItem
import com.appsbyayush.noteit.repo.NoteRepository
import com.appsbyayush.noteit.utils.Resource
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.BeginSignInResult
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val app: Application
): ViewModel() {

    companion object {
        private const val TAG = "HomeViewModelyy"
    }

    private val _eventStateFlow = MutableStateFlow<Event>(Event.Idle)
    val events = _eventStateFlow.asStateFlow()

    var notesFlow: StateFlow<Resource<List<Note>>> = MutableStateFlow(Resource.Loading())
    var loggedInUser = repository.getAuthenticatedUser()
    var signInClient: SignInClient? = null

    private val _appSettingsStateFlow = MutableStateFlow(AppSettings())
    val appSettings = _appSettingsStateFlow.asStateFlow()

    private val _searchQueryFlow = MutableStateFlow("")
    val searchQuery = _searchQueryFlow.asStateFlow()

    init {
        Log.d(TAG, "User: ${loggedInUser?.email}: ")
    }

    fun onFragmentStarted() {
       notesFlow = _appSettingsStateFlow.combine(_searchQueryFlow) { settings, searchQuery ->
           Pair(settings, searchQuery)
       }.flatMapLatest {
           val settings = it.first
           val searchQuery = it.second

           repository.getAllNotes(settings.currentSort, searchQuery)
       }.stateIn(viewModelScope, SharingStarted.Lazily, Resource.Loading())

        _appSettingsStateFlow.value = repository.getAppSettings()

        loggedInUser = repository.getAuthenticatedUser()
    }

    fun updateCurrentSort(sortItem: SortItem) {
        _appSettingsStateFlow.update {
            val updatedAppSettings = it.copy(currentSort = sortItem)
            repository.saveAppSettings(updatedAppSettings)
            updatedAppSettings
        }
    }

    fun updateSearchQuery(searchQuery: String) {
        _searchQueryFlow.value = searchQuery
    }

    fun trashNotes(noteList: List<Note>) = viewModelScope.launch {
        val trashedNotes = noteList.map {
            it.isDeleted = true
            it
        }

        repository.insertNoteListLocally(trashedNotes)
        _eventStateFlow.emit(Event.TrashNotesSuccess)
    }

    fun loginUserWithGoogle() = viewModelScope.launch {
        Log.d(TAG, "loginUserWithGoogle: Called")
        signInClient?.let { oneTapClient ->
            try {
                sendEvent(Event.Loading)

                val googleIdTokenRequestOptions = BeginSignInRequest.GoogleIdTokenRequestOptions
                    .builder()
                    .setSupported(true)
                    .setServerClientId(app.applicationContext.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()

                val signInRequest = BeginSignInRequest.builder()
                    .setGoogleIdTokenRequestOptions(googleIdTokenRequestOptions)
                    .setAutoSelectEnabled(true)
                    .build()

                val beginSignInResult = oneTapClient.beginSignIn(signInRequest).await()
                _eventStateFlow.emit(Event.BeginOneTapSignInSuccess(beginSignInResult))

            } catch(e: Exception) {
                Log.d(TAG, "loginUserWithGoogle: ${e.message}")
                if(e is ApiException) {
                    _eventStateFlow.emit(Event.BeginOneTapSignInFailure(e))
                }
            }
        }
    }

    fun onIntentSenderResultRetrieved(intent: Intent?) {
        if(intent == null) {
            val exception = Exception("Some error occurred")
            sendEvent(Event.ErrorOccurred(exception))
            return
        }

        signInClient?.let { oneTapClient ->
            try {
                sendEvent(Event.Loading)

                val credential = oneTapClient.getSignInCredentialFromIntent(intent)
                val idToken = credential.googleIdToken
                val username = credential.id
                val password = credential.password

                when {
                    idToken != null -> {
                        Log.d(TAG, "onIntentSenderResultRetrieved -- token: $idToken")
                        signInUserWithCredentials(idToken)
                    }

                    password != null -> {
                        Log.d(TAG, "onIntentSenderResultRetrieved -- password: $username $password")
                    }

                    else -> {
                        val exception = Exception("Some error occurred")
                        sendEvent(Event.ErrorOccurred(exception))
                    }
                }
            } catch(e: Exception) {
                if(e is ApiException && e.statusCode == CommonStatusCodes.CANCELED) {
                    Log.d(TAG, "onIntentSenderResultRetrieved: One Tap Dialog Closed by User")
                }

                sendEvent(Event.ErrorOccurred(e))
                Log.d(TAG, "onIntentSenderResultRetrieved: ${e.message}")
            }
        }
    }

    private fun signInUserWithCredentials(idToken: String) = viewModelScope.launch {
        try {
            sendEvent(Event.Loading)

            repository.firebaseSignInWithCredentials(idToken)
            loggedInUser = repository.getAuthenticatedUser()

            sendEvent(Event.SignInSuccess)
        } catch(e: Exception) {
            Log.d(TAG, "signInUserWithCredentials: ${e.message}")
            sendEvent(Event.ErrorOccurred(e))
        }
    }

    fun showSignupMessage(): Boolean {
        val timeLapsedSinceSignupMessageShown = (System.currentTimeMillis()
                - _appSettingsStateFlow.value.signupPopupLastShownTimestamp)

        Log.d(TAG, "showSignupMessage: timeLapsedSinceSignupMessageShown: $timeLapsedSinceSignupMessageShown")
        Log.d(TAG, "App Settings: ${_appSettingsStateFlow.value}")

        return (timeLapsedSinceSignupMessageShown > TimeUnit.HOURS.toMillis(2)
                && loggedInUser == null)
    }

    fun updateSignupPopupLastShownTime() {
        _appSettingsStateFlow.update {
            val updatedAppSettings = it.copy(signupPopupLastShownTimestamp = System.currentTimeMillis())
            repository.saveAppSettings(updatedAppSettings)
            updatedAppSettings
        }
    }

    fun saveCurrentSyncProcessId(processId: String) {
        repository.saveCurrentSyncProcessId(processId)
    }

    fun getCurrentSyncProcessId(): String {
        return repository.getCurrentSyncProcessId()
    }

    fun getUpdatedAppSettings(): AppSettings {
        _appSettingsStateFlow.update {
            repository.getAppSettings()
        }

        return repository.getAppSettings()
    }

    private fun sendEvent(event: Event) = viewModelScope.launch {
        _eventStateFlow.emit(event)
    }

    fun onEventOccurred() = viewModelScope.launch {
        _eventStateFlow.emit(Event.Idle)
    }

    sealed class Event {
        object SignInSuccess: Event()
        class BeginOneTapSignInSuccess(val result: BeginSignInResult): Event()
        class BeginOneTapSignInFailure(val exception: Exception): Event()
        object TrashNotesSuccess: Event()
        class ErrorOccurred(val exception: Throwable): Event()
        object Loading: Event()
        object Idle : Event()
    }
}