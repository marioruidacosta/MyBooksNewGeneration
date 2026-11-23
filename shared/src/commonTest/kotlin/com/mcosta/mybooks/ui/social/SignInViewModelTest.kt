package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.social.FakeSocial
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignInViewModelTest : MainDispatcherTest() {
    private val fake = FakeSocial()

    private fun vm() = SignInViewModel(fake.auth, fake.profileService)

    @Test
    fun emailThenCodeThenUsernameCreatesProfile() = runTest {
        val vm = vm()
        assertEquals(SignInStep.EMAIL, vm.state.value.step)
        vm.setEmail(" ana@exemplo.pt ")
        vm.sendCode()
        assertEquals(SignInStep.CODE, vm.state.value.step)
        vm.setCode("123456")
        vm.verify()
        assertEquals(SignInStep.USERNAME, vm.state.value.step)
        vm.setUsername("ana_r")
        advanceTimeBy(301)
        assertEquals(true, vm.state.value.usernameAvailable)
        vm.setDisplayName("Ana R")
        vm.createProfile()
        assertEquals(SignInStep.DONE, vm.state.value.step)
        assertEquals("ana_r", fake.profileService.myProfile()?.username)
    }

    @Test
    fun theClockRestartsBeforeTheCodeIsVerified() = runTest {
        var restarted = false
        val vm = SignInViewModel(fake.auth, fake.profileService, beforeVerify = { restarted = true })
        vm.setEmail("ana@exemplo.pt")
        vm.sendCode()
        vm.setCode("123456")
        vm.verify()
        assertEquals(true, restarted)
    }

    @Test
    fun invalidEmailRejectedLocally() = runTest {
        val vm = vm()
        vm.setEmail("name@")
        vm.sendCode()
        assertEquals("Enter a valid email", vm.state.value.error)
        assertEquals(SignInStep.EMAIL, vm.state.value.step)
    }

    @Test
    fun wrongCodeShowsMessageAndAllowsResend() = runTest {
        val vm = vm()
        vm.setEmail("ana@exemplo.pt")
        vm.sendCode()
        vm.setCode("000000")
        vm.verify()
        assertEquals("That code is wrong or has expired", vm.state.value.error)
        assertEquals(SignInStep.CODE, vm.state.value.step)
        assertEquals("000000", vm.state.value.code)
        vm.resend()
        assertNull(vm.state.value.error)
        assertEquals("We sent a new code", vm.state.value.info)
    }

    @Test
    fun codeMustBeSixDigits() = runTest {
        val vm = vm()
        vm.setEmail("ana@exemplo.pt")
        vm.sendCode()
        vm.setCode("12a4")
        assertEquals("124", vm.state.value.code)
        vm.verify()
        assertEquals("Enter the 6-digit code", vm.state.value.error)
    }

    @Test
    fun usernameIsNormalizedAndValidatedLocally() = runTest {
        fake.signInAs("id-new")
        val vm = vm()
        vm.setUsername(" Mario C ")
        advanceTimeBy(301)
        assertEquals("mario c", vm.state.value.username)
        assertEquals("3–20 characters: a–z, 0–9, _ and .", vm.state.value.usernameError)
        assertEquals(0, fake.availabilityChecks)
    }

    @Test
    fun availabilityIsCheckedOnceAfterTypingStops() = runTest {
        fake.signInAs("id-new")
        val vm = vm()
        vm.setUsername("mar")
        vm.setUsername("mari")
        vm.setUsername("mario")
        advanceTimeBy(301)
        assertEquals(1, fake.availabilityChecks)
    }

    @Test
    fun takenUsernameShowsMessage() = runTest {
        fake.addUser("bruno")
        fake.signInAs("id-new")
        val vm = vm()
        vm.setUsername("bruno")
        advanceTimeBy(301)
        assertEquals(false, vm.state.value.usernameAvailable)
        vm.setDisplayName("Bruno Two")
        vm.createProfile()
        assertEquals("That username is taken", vm.state.value.error)
        assertEquals(SignInStep.USERNAME, vm.state.value.step)
    }

    @Test
    fun blankDisplayNameRejected() = runTest {
        fake.signInAs("id-new")
        val vm = vm()
        vm.setUsername("newbie")
        vm.setDisplayName("  ")
        vm.createProfile()
        assertEquals("Enter a name", vm.state.value.error)
    }

    @Test
    fun signedInWithoutProfileGoesToUsernameStep() = runTest {
        fake.signInAs("id-new")
        assertEquals(SignInStep.USERNAME, vm().state.value.step)
    }

    @Test
    fun signedInWithProfileIsDone() = runTest {
        fake.signInAs(fake.addUser("ana"))
        assertEquals(SignInStep.DONE, vm().state.value.step)
    }

    @Test
    fun startsLoadingNotOnTheEmailForm() {
        assertEquals(SignInStep.LOADING, SignInState().step)
    }

    @Test
    fun offlineProfileCheckShowsRetryNotEmailForm() = runTest {
        fake.signInAs("id-new")
        fake.offline = true
        val vm = vm()
        assertEquals(SignInStep.LOADING, vm.state.value.step)
        assertEquals("You're offline", vm.state.value.error)
        fake.offline = false
        vm.retry()
        assertEquals(SignInStep.USERNAME, vm.state.value.step)
    }

    @Test
    fun followsSessionChanges() = runTest {
        val vm = vm()
        assertEquals(SignInStep.EMAIL, vm.state.value.step)
        fake.signInAs(fake.addUser("ana"))
        assertEquals(SignInStep.DONE, vm.state.value.step)
        fake.auth.signOut()
        assertEquals(SignInStep.EMAIL, vm.state.value.step)
    }

    @Test
    fun offlineShowsOfflineMessage() = runTest {
        val vm = vm()
        fake.offline = true
        vm.setEmail("ana@exemplo.pt")
        vm.sendCode()
        assertEquals("You're offline", vm.state.value.error)
        assertEquals(SignInStep.EMAIL, vm.state.value.step)
    }

    @Test
    fun profileCreationNotifies() = runTest {
        var created = 0
        fake.signInAs("id-new-0")
        val vm = SignInViewModel(fake.auth, fake.profileService, onProfileCreated = { created++ })
        vm.setUsername("newbie")
        vm.setDisplayName("Newbie")
        vm.createProfile()
        assertEquals(SignInStep.DONE, vm.state.value.step)
        assertEquals(1, created)
    }
}
