package com.priyo.people

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDexApplication
import androidx.room.Room
import com.cloudinary.android.LogLevel
import com.cloudinary.android.MediaManager
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.priyo.people.activity.AppTourActivity
import com.priyo.people.api.response.GenericResponse
import com.priyo.people.model.UserActivityRequest
import com.priyo.people.model.UserInfo
import com.priyo.people.repository.factory.PriyoRepositoryFactory
import com.priyo.people.repository.factory.SimplePriyoRepositoryFactory
import com.priyo.people.util.*
import com.priyo.people.util.constant.ApiConstants
import com.priyo.people.util.constant.DatabaseConstants
import com.priyo.people.util.constant.PriyoGoConstants
import com.priyo.people.util.constant.SharedPreferenceConstants
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Function
import io.reactivex.schedulers.Schedulers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit

class PriyoApplication : MultiDexApplication(), LifecycleObserver {

    override fun onCreate() {
        super.onCreate()

        instance = this
        ProcessLifecycleOwner.get().lifecycle.addObserver(this);

        // Setting up preferences
        preferences = getSharedPreferences(
            SharedPreferenceConstants.TAG_PRIYO,
            Context.MODE_PRIVATE
        )

        //Database Config
        priyoDatabase = Room.databaseBuilder(
            this,
            PriyoDatabase::class.java,
            DatabaseConstants.PRIYO_DATABASE
        )
            .allowMainThreadQueries()
            .build()

        // Singleton Repository Config
        repositoryFactory = SimplePriyoRepositoryFactory.create()

        PRIYO_AUTH_RETROFIT_OLD = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_AUTH_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_NON_NULL_GSON))
            .build()!!

        // Cloudinary Image Upload Config
        val config = HashMap<String, Any>()
        config["cloud_name"] = "priyo"
        config["api_key"] = "335149991454134"
        config["api_secret"] = "4m0xe-Ssks_oxVvP6EVuGXszDCE"
        config["enhance_image_tag"] = true
        config["static_image_support"] = false
        config["private_cdn"] = true
        config["secure_distribution"] = "priyo-res.cloudinary.com"
        config["secure"] = true
        config["cname"] = "media.priyo.com"
        MediaManager.init(this, config)
        MediaManager.setLogLevel(LogLevel.DEBUG)

        // GoogleApiClient
        buildGoogleApiClient()
        googleApiClient.connect()

        val mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setDeveloperModeEnabled(BuildConfig.DEBUG)
            .build()
        mFirebaseRemoteConfig.setConfigSettings(configSettings)
        mFirebaseRemoteConfig.setDefaults(R.xml.remote_config_defaults)
        MyPreferenceUtils.initialize(applicationContext)


//        if (!SharedPreferenceUtil.getSessionId().isNullOrEmpty()) {
//            disposable = Observable.interval(
//                30, 30,
//                TimeUnit.SECONDS
//            )
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(
//                    { aLong: Long? ->
//                        callJokesEndpoint(
//                            aLong!!
//                        )
//                    }
//                ) { throwable: Throwable? ->
//                    onError(
//                        throwable!!
//                    )
//                }
//        }


    }

    @Synchronized
    private fun buildGoogleApiClient() {
        googleApiClient = GoogleApiClient.Builder(this)
            .addConnectionCallbacks(object : GoogleApiClient.ConnectionCallbacks {
                override fun onConnected(p0: Bundle?) {
                    PriyoLog.d(TAG, "GoogleApiClient Connection Successful")
                }

                override fun onConnectionSuspended(p0: Int) {
                    when (p0) {
                        GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST -> {
                            PriyoLog.d(
                                TAG,
                                "Network Connection lost with play services. Retrying..."
                            )
                        }
                        GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED -> {
                            PriyoLog.d(TAG, "Disconnected with play services. Retrying...")
                        }
                    }
                    googleApiClient.connect()
                }

            })
            .addOnConnectionFailedListener {
                PriyoLog.d(TAG, it.errorMessage ?: "Can't Connect to Google Play Services")
                showShortToast("Can't Connect to Google Play Services")
            }
            .addApi(LocationServices.API)
            .build()
    }


    companion object {
        private val TAG = PriyoApplication::class.java.simpleName

        lateinit var googleApiClient: GoogleApiClient
            private set
        lateinit var priyoDatabase: PriyoDatabase
            private set
        lateinit var preferences: SharedPreferences
            private set

        lateinit var repositoryFactory: PriyoRepositoryFactory
            private set

        lateinit var instance: PriyoApplication
            private set

        private fun logger(): HttpLoggingInterceptor {
            val logger = HttpLoggingInterceptor(HttpLogger())
            logger.level = HttpLoggingInterceptor.Level.BODY
            return logger
        }


        lateinit var disposable: Disposable

        lateinit var PRIYO_AUTH_RETROFIT_OLD: Retrofit



        private val priyoOkHttpClient = OkHttpClient.Builder()
            .readTimeout(PriyoGoConstants.CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
            .addInterceptor(logger())
            .addNetworkInterceptor {
                it.proceed(
                    it.request().newBuilder()
                        .header(
                            ApiConstants.AUTHORIZATION_KEY,
                            SharedPreferenceUtil.getPriyoAuthToken()
                        )
                        .header(ApiConstants.TOKEN, SharedPreferenceUtil.getPriyoAccessToken())
                        .header(
                            ApiConstants.USER_AGENT_HEADER,
                            ApiConstants.USER_AGENT_MOBILE_ANDROID
                        )
                        .build()
                )
            }
            .addInterceptor(Interceptor { chain ->
                val request: Request = chain.request()
                val response = chain.proceed(request)

                // todo deal with the issues the way you need to
                if (response.code() == HttpStatusCode.HTTP_UNAUTHORIZED) {

                    val myApplicationInstance: PriyoApplication = instance
                    val loggedIn: Boolean = SharedPreferenceUtil.isLoggedIn()

                    if (loggedIn) {
                        myApplicationInstance.launchLoginPage()
                    }
//                    startActivity(
//                        Intent(
//                            this@ErrorHandlingActivity,
//                            ServerIsBrokenActivity::class.java
//                        )
//                    )
                    return@Interceptor response
                }
                response
            })
            .build()

        val PRIYO_AUTH_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_AUTH_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_NON_NULL_GSON))
            .build()!!

        val PRIYO_AUTH_RETROFIT_V2 = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_AUTH_BASE_URL_V2)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_NON_NULL_GSON))
            .build()!!

        val PRIYO_LOOP_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_LOOP_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_NON_NULL_GSON))
            .build()!!

        val PRIYO_GO_CONTACT_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_AUTH_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.HIDE_NON_EXPOSED_GSON))
            .build()!!

        val PRIYO_GO_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_GO_BASE_URL)
            .client(OkHttpClient.Builder()
                .readTimeout(PriyoGoConstants.CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .addNetworkInterceptor {
                    it.proceed(
                        it.request().newBuilder()
                            .header(
                                ApiConstants.AUTHORIZATION_KEY,
                                SharedPreferenceUtil.getPriyoAuthToken()
                            )
                            .header(
                                ApiConstants.TOKEN,
                                SharedPreferenceUtil.getPriyoAccessToken()
                            )
                            .header(
                                ApiConstants.USER_AGENT_HEADER,
                                ApiConstants.USER_AGENT_MOBILE_ANDROID
                            )
                            .header(
                                ApiConstants.ACCEPT_HEADER,
                                ApiConstants.ACCEPT_HEADER_JSON_UTF_8
                            )
                            .header(
                                ApiConstants.PRIYO_GO_API_KEY_HEADER,
                                ApiConstants.BIDDING_API_KEY
                            )
                            .build()
                    )
                }
                .addInterceptor(logger())
                .build())
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!



        val PRIYO_NULL_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_AUTH_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_NON_NULL_GSON))
            .build()!!


        val PRIYO_GO_AUTHOR_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_GO_BASE_AUTHOR_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_OLD_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_OLD_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_ANALYTICS_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_ANALYTICS_BASE_URL)
            .client(priyoOkHttpClient)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_REFERAL_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_REFERAL_BASE_URL)
            .client(priyoOkHttpClient)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_QUIZ_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_QUIZ_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_LAPTOP_RETROFIT = Retrofit.Builder()
                .baseUrl(ApiConstants.PRIYO_LAPTOP_BASE_URL)
                .client(priyoOkHttpClient)
                .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
                .build()!!

        val PRIYO_LIVEQUIZ_RETROFIT = Retrofit.Builder()
                .baseUrl(ApiConstants.PRIYO_LIVE_QUIZ_BASE_URL)
                .client(priyoOkHttpClient)
                .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
                .build()!!

        val PRIYO_NILAM_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_NILAM_BASE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_FIREBASE_RETROFIT = Retrofit.Builder()
            .baseUrl("https://priyo-438fd.firebaseio.com/")
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()!!

        val PRIYO_YOUTUBE_RETROFIT = Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/youtube/v3/")
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()

        val PRIYO_LOCATION_SERVISE_RETROFIT = Retrofit.Builder()
            .baseUrl(ApiConstants.PRIYO_LOCATION_SERVICE_URL)
            .client(priyoOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(CommonConstants.DEFAULT_GSON))
            .build()


        var isAppInBackground = false
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onMoveToForeground() {
        isAppInBackground = false
        val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        if(SharedPreferenceUtil.getSessionId().isNullOrEmpty()) {
            val randomString = (1..13)
                .map { i -> kotlin.random.Random.nextInt(0, charPool.size) }
                .map(charPool::get)
                .joinToString("");
            SharedPreferenceUtil.setSessionId(randomString)
        }
        if (!SharedPreferenceUtil.getSessionId().isNullOrEmpty()) {
            disposable = Observable.interval(
                10, 30,
                TimeUnit.SECONDS
            )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { aLong: Long? ->
                        callJokesEndpoint(
                            aLong!!
                        )
                    }
                ) { throwable: Throwable? ->
                    onError(
                        throwable!!
                    )
                }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onMoveToBackground() {
        isAppInBackground = true
        SharedPreferenceUtil.setSessionId("")
        disposable.dispose()
    }

    @SuppressLint("CheckResult")
    private fun callJokesEndpoint(aLong: Long) {
        val userInfo = UserInfo()
        userInfo.email = SharedPreferenceUtil.getUserEmail()
        userInfo.name = SharedPreferenceUtil.getFirstName()
        userInfo.userUid = SharedPreferenceUtil.getUUID()
        userInfo.image = SharedPreferenceUtil.getProfilePictureUrl()
        val userActivityRequest = UserActivityRequest()
        userActivityRequest.deviceUid = applicationContext.getDeviceId()
        userActivityRequest.userId = SharedPreferenceUtil.getUserId()
        userActivityRequest.sessionId = SharedPreferenceUtil.getSessionId()
        userActivityRequest.userInfo = userInfo
        val observable: Observable<GenericResponse> = RetrofitConfig.priyoAnalyticsService.uploadUserActivity(
            userActivityRequest
        )
        observable.subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .map(Function<GenericResponse, Any> { result: GenericResponse -> result.isSuccessful })
            .subscribe(
                {
                }
            ) { t: Throwable ->
                handleError(
                    t
                )
            }
    }

    private fun onError(throwable: Throwable) {
//        System.out.println("51.89 " + throwable.message + " " + throwable.localizedMessage)
//        Toast.makeText(
//            this, throwable.message,
//            Toast.LENGTH_LONG
//        ).show()
    }


    private fun handleResults(joke: String) {
    }

    private fun handleError(t: Throwable) {
//        Toast.makeText(
//            this, t.localizedMessage.toString(),
//            Toast.LENGTH_LONG
//        ).show()

        //Add your error here.
    }

    // Launch login page for token timeout/un-authorized/logout called for user inactivity
    fun launchLoginPage() {
        val loggedIn: Boolean = SharedPreferenceUtil.isLoggedIn()
        if (!loggedIn) return
        if (!isAppInBackground) {
            SharedPreferenceUtil.setLoggedIn(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                clearAppShortCut()
            }
            SharedPreferenceUtil.clearAllPreferences();
            val intent = Intent(applicationContext, AppTourActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
    }
}