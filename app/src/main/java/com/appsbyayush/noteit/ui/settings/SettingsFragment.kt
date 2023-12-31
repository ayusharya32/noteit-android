package com.appsbyayush.noteit.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.*
import com.appsbyayush.noteit.databinding.FragmentSettingsBinding
import com.appsbyayush.noteit.utils.Constants
import com.appsbyayush.noteit.utils.getNetworkStatus
import com.appsbyayush.noteit.worker.SyncWorker
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
import com.appsbyayush.noteit.R
import com.appsbyayush.noteit.baseactivity.TermsActivity
import com.appsbyayush.noteit.models.Note
import com.appsbyayush.noteit.utils.CommonMethods
import com.google.android.material.dialog.MaterialAlertDialogBuilder

@AndroidEntryPoint
class SettingsFragment: Fragment() {
    companion object {
        private const val TAG = "SettingsFragmentyy"
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var logoutLoadingDialog: AlertDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "onViewCreated: Logged In User : ${viewModel.loggedInUser?.email}")

        viewModel.signInClient = context?.let {
            GoogleSignIn.getClient(it, viewModel.getGoogleSignInOptions())
        }

        setupButtons()
        setupLogoutLoadingDialog()

        setupUserDetails()
        setupLastSyncDetails()
        setupSyncObserver()
        setupUIEventCollector()
    }

    private fun setupLogoutLoadingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout_loading, binding.root, false)

        logoutLoadingDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DialogOverlay).apply {
            setView(dialogView)
            setCancelable(false)
        }.create()
    }

    private fun setupSyncObserver(processId: String? = null) {
        context?.let {
            var savedSyncProcessId = viewModel.getCurrentSyncProcessId()

            if(savedSyncProcessId.isEmpty() && processId.isNullOrEmpty()) {
                return
            }

            val currentSyncProcessId = processId ?: savedSyncProcessId

            WorkManager.getInstance(it)
                .getWorkInfoByIdLiveData(UUID.fromString(currentSyncProcessId))
                .observe(viewLifecycleOwner) { workInfo ->
                    Log.d(TAG, "setupSyncObserver: $workInfo")

                    when(workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                            binding.btnSyncNotes.apply {
                                text = "Syncing Notes..."
                                icon = null
                                isEnabled = false
                            }
                        }

                        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> {
                            binding.btnSyncNotes.apply {
                                text = "Sync Notes"
                                icon = AppCompatResources.getDrawable(requireContext(),
                                    R.drawable.ic_sync)
                                isEnabled = true

                                viewModel.saveCurrentSyncProcessId("")
                            }
                        }
                    }

                    if(workInfo.state == WorkInfo.State.SUCCEEDED) {
                        Toast.makeText(context, "Notes synced successfully", Toast.LENGTH_SHORT).show()
                        setupLastSyncDetails()
                    }
                }
        }
    }

    private fun setupUserDetails() {
        viewModel.loggedInUser?.let { user ->
            binding.apply {
                txtUserName.text = user.displayName
                txtUserEmail.text = user.email

                Glide.with(root)
                    .load(user.photoUrl)
                    .into(imgUser)
            }
        }
    }

    private fun setupLastSyncDetails() {
        binding.apply {
            val lastSync = viewModel.getUpdatedAppSettings().lastSyncTime
            txtLastSyncTime.isVisible = lastSync != null

            if(lastSync != null) {
                val lastSyncString = "Last Sync: ${CommonMethods.getTimeAgoString(lastSync)} " +
                        "(${CommonMethods.getFormattedDateTime(lastSync, Constants.DATE_FORMAT_2)})"
                txtLastSyncTime.text = lastSyncString
            }
        }
    }

    private fun setupButtons() {
        binding.btnSignIn.setOnClickListener {
            viewModel.signInClient?.let {
                googleSignInLauncher.launch(it.signInIntent)
            }
        }

        binding.btnSignOut.setOnClickListener {
            showSignOutDialog()
        }

        binding.btnSyncNotes.setOnClickListener {
            syncNotesImmediately()
        }

        binding.btnToolbarBack.setOnClickListener {
            activity?.onBackPressed()
        }

        binding.btnTrashCan.setOnClickListener {
            findNavController().navigate(SettingsFragmentDirections
                .actionFragmentSettingsToTrashFragment())
        }

        binding.txtPrivacyPolicy.setOnClickListener {
            Intent(context, TermsActivity::class.java).also {
                startActivity(it)
            }
        }
    }

    private fun showSignOutDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext()).apply {
            setTitle("Sign Out")
            setMessage(getString(R.string.sign_out_message))
            setPositiveButton("Confirm") { dialog, _ ->
                viewModel.signOut()
                dialog.dismiss()
            }
            setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
        }.create()

        dialog.show()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isAllCaps = false
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isAllCaps = false
    }

    private fun syncNotesImmediately() {
        context?.let {
            if(getNetworkStatus(it) == 0) {
                Toast.makeText(it, "No internet connection..", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if(viewModel.loggedInUser == null) {
            context?.let {
                WorkManager.getInstance(it).cancelUniqueWork(SyncWorker.ONE_TIME_REQUEST_NAME)
            }
            return
        }

        val workRequestConstraints = Constraints.Builder().apply {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()

        val syncOneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(workRequestConstraints)
            .build()

        context?.let {
            WorkManager.getInstance(it).enqueueUniqueWork(
                SyncWorker.ONE_TIME_REQUEST_NAME, ExistingWorkPolicy.KEEP, syncOneTimeRequest)
        }

        val syncProcessId = syncOneTimeRequest.id.toString()

        viewModel.saveCurrentSyncProcessId(syncProcessId)
        setupSyncObserver(syncProcessId)
    }

    private fun setupFragmentViews(loading: Boolean = false) {
        binding.apply {
            progressLoading.isVisible = loading

            llNotLoggedIn.isVisible = !loading && viewModel.loggedInUser == null
            llUserInfo.isVisible = !loading && viewModel.loggedInUser != null

            btnSyncNotes.isVisible = !loading && viewModel.loggedInUser != null
            btnSignOut.isVisible = !loading && viewModel.loggedInUser != null
            btnTrashCan.isVisible = !loading
        }
    }

    private fun setupUIEventCollector() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                setupFragmentViews(false)

                when(event) {
                    is SettingsViewModel.Event.SignInSuccess -> {
                        Toast.makeText(context, "Sign in successful", Toast.LENGTH_SHORT).show()
                        setupFragmentViews()
                        setupUserDetails()
                        addSyncNotesWorker()
                    }

                    is SettingsViewModel.Event.LogoutLoading -> {
                        logoutLoadingDialog.show()
                    }

                    is SettingsViewModel.Event.LogoutSuccess -> {
                        Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                        logoutLoadingDialog.dismiss()

                        findNavController().popBackStack()
                    }

                    is SettingsViewModel.Event.LogoutError -> {
                        Toast.makeText(context, event.exception.message, Toast.LENGTH_SHORT).show()
                        logoutLoadingDialog.dismiss()
                    }

                    is SettingsViewModel.Event.ErrorOccurred -> {
                        Toast.makeText(context, event.exception.message, Toast.LENGTH_SHORT).show()
                    }

                    is SettingsViewModel.Event.Loading -> {
                        setupFragmentViews(true)
                    }

                    is SettingsViewModel.Event.Idle -> {}
                }
            }
        }
    }

    private fun addSyncNotesWorker() {
        if(viewModel.loggedInUser == null) {
            context?.let {
                WorkManager.getInstance(it).cancelUniqueWork(SyncWorker.PERIODIC_REQUEST_NAME)
            }
            return
        }

        val workRequestConstraints = Constraints.Builder().apply {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()

        val syncPeriodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
            .setConstraints(workRequestConstraints)
            .build()

        context?.let {
            WorkManager.getInstance(it).enqueueUniquePeriodicWork(
                SyncWorker.PERIODIC_REQUEST_NAME, ExistingPeriodicWorkPolicy.KEEP, syncPeriodicRequest)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Called")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Called")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Called")
        setupFragmentViews()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Called")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
            viewModel.onSigninIntentResultRetrieved(it.data)
    }
}