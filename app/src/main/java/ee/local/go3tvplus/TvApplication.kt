package ee.local.go3tvplus

import android.app.Application
import ee.local.go3tvplus.catalog.CatalogRepository
import ee.local.go3tvplus.data.AuthCoordinator
import ee.local.go3tvplus.data.DemoGo3Gateway
import ee.local.go3tvplus.data.Go3HttpGateway
import ee.local.go3tvplus.data.IlmateenistusGateway
import ee.local.go3tvplus.data.OpenMeteoWeatherGateway
import ee.local.go3tvplus.data.PeatusTransitGateway
import ee.local.go3tvplus.data.TvRepository
import ee.local.go3tvplus.data.local.AppDatabase
import ee.local.go3tvplus.data.local.KeystoreTokenStore
import ee.local.go3tvplus.data.local.TvPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TvApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context) = ee.local.go3tvplus.catalog.catalogImageLoader(context)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = if (BuildConfig.DEMO_MODE) DemoGo3Gateway() else Go3HttpGateway(this)
        val auth = AuthCoordinator(gateway, KeystoreTokenStore(this), scope)
        val database = AppDatabase.create(this)
        container = AppContainer(
            auth = auth,
            repository = TvRepository(gateway, auth, database, scope),
            catalog = CatalogRepository(gateway, auth, database, scope),
            preferences = TvPreferences(this),
            weather = OpenMeteoWeatherGateway(),
            stations = IlmateenistusGateway(),
            transit = PeatusTransitGateway(),
            isDemo = BuildConfig.DEMO_MODE,
        )
    }
}

data class AppContainer(
    val auth: AuthCoordinator,
    val repository: TvRepository,
    val catalog: CatalogRepository,
    val preferences: TvPreferences,
    val weather: OpenMeteoWeatherGateway,
    val stations: IlmateenistusGateway,
    val transit: PeatusTransitGateway,
    val isDemo: Boolean,
)
