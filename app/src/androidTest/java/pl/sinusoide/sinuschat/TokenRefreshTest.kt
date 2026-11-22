package pl.sinusoide.sinuschat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.sinusoide.sinuschat.network.ApiClient

@RunWith(AndroidJUnit4::class)
class TokenRefreshTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun refreshGivesWorkingToken() {
        val old = ApiClient.getToken(ctx)
        assertNotNull("brak sesji na telefonie", old)
        Thread.sleep(1100)
        val fresh = ApiClient.post("/api/token/refresh", JSONObject(), ctx)?.optString("token")
        assertTrue("serwer nie odnowił sesji", !fresh.isNullOrBlank())
        assertNotEquals(old, fresh)
        ApiClient.saveToken(ctx, fresh!!)
        val limits = ApiClient.getObject("/api/files/limits", ctx)
        assertNotNull("nowa sesja nie działa", limits)
        assertTrue(limits!!.optLong("max") > 0)
    }
}
