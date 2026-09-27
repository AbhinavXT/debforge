package com.abhinavxt.debforge;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.abhinavxt.debforge.data.backup.BackupManager;
import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.local.ChunkDao;
import com.abhinavxt.debforge.data.local.DebForgeDatabase;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.metadata.MediaMetaDao;
import com.abhinavxt.debforge.data.metadata.MetadataRepository;
import com.abhinavxt.debforge.data.metadata.TmdbApi;
import com.abhinavxt.debforge.data.playback.PlaybackDao;
import com.abhinavxt.debforge.data.playback.PlaybackPositions;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.abhinavxt.debforge.data.provider.DebridProvider;
import com.abhinavxt.debforge.data.provider.ProviderRegistry;
import com.abhinavxt.debforge.data.provider.alldebrid.AllDebridApi;
import com.abhinavxt.debforge.data.provider.alldebrid.AllDebridProvider;
import com.abhinavxt.debforge.data.provider.debridlink.DebridLinkApi;
import com.abhinavxt.debforge.data.provider.debridlink.DebridLinkProvider;
import com.abhinavxt.debforge.data.provider.premiumize.PremiumizeApi;
import com.abhinavxt.debforge.data.provider.premiumize.PremiumizeProvider;
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridApi;
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridOAuthApi;
import com.abhinavxt.debforge.data.provider.realdebrid.RealDebridProvider;
import com.abhinavxt.debforge.data.provider.torbox.TorBoxApi;
import com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider;
import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
import com.abhinavxt.debforge.data.update.UpdateChecker;
import com.abhinavxt.debforge.data.usage.UsageDao;
import com.abhinavxt.debforge.data.usage.UsageRecorder;
import com.abhinavxt.debforge.di.AppModule_ProvideContextFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideChunkDaoFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideDatabaseFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideDownloadDaoFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideMediaMetaDaoFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvidePlaybackDaoFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideUsageDaoFactory;
import com.abhinavxt.debforge.di.DatabaseModule_ProvideWatchDaoFactory;
import com.abhinavxt.debforge.di.MetadataModule_ProvideTmdbApiFactory;
import com.abhinavxt.debforge.di.NetworkModule_ProvideApiOkHttpFactory;
import com.abhinavxt.debforge.di.NetworkModule_ProvideDownloadOkHttpFactory;
import com.abhinavxt.debforge.di.NetworkModule_ProvideMoshiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvideAllDebridApiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvideDebridLinkApiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvidePremiumizeApiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvideRealDebridApiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvideRealDebridOAuthApiFactory;
import com.abhinavxt.debforge.di.ProviderModule_ProvideTorBoxApiFactory;
import com.abhinavxt.debforge.download.ChunkedDownloader;
import com.abhinavxt.debforge.download.CompletionNotifier;
import com.abhinavxt.debforge.download.DownloadController;
import com.abhinavxt.debforge.download.DownloadJobService;
import com.abhinavxt.debforge.download.DownloadJobService_MembersInjector;
import com.abhinavxt.debforge.download.DownloadProgressTracker;
import com.abhinavxt.debforge.download.DownloadQueueRunner;
import com.abhinavxt.debforge.download.DownloadScheduler;
import com.abhinavxt.debforge.download.DownloadService;
import com.abhinavxt.debforge.download.DownloadService_MembersInjector;
import com.abhinavxt.debforge.download.Housekeeper;
import com.abhinavxt.debforge.download.NetworkMonitor;
import com.abhinavxt.debforge.download.ScheduleAlarmReceiver;
import com.abhinavxt.debforge.download.ScheduleAlarmReceiver_MembersInjector;
import com.abhinavxt.debforge.download.ScheduleRearmReceiver;
import com.abhinavxt.debforge.download.ScheduleRearmReceiver_MembersInjector;
import com.abhinavxt.debforge.download.ServiceCleaner;
import com.abhinavxt.debforge.download.conditions.DownloadConditions;
import com.abhinavxt.debforge.download.conditions.PowerMonitor;
import com.abhinavxt.debforge.download.conditions.RateLimiter;
import com.abhinavxt.debforge.download.follow.FollowChecker;
import com.abhinavxt.debforge.download.follow.FollowJobService;
import com.abhinavxt.debforge.download.follow.FollowJobService_MembersInjector;
import com.abhinavxt.debforge.download.watch.ReadyWatchJobService;
import com.abhinavxt.debforge.download.watch.ReadyWatchJobService_MembersInjector;
import com.abhinavxt.debforge.download.watch.ReadyWatcher;
import com.abhinavxt.debforge.download.watch.WatchDao;
import com.abhinavxt.debforge.player.PlayerActivity;
import com.abhinavxt.debforge.player.PlayerActivity_MembersInjector;
import com.abhinavxt.debforge.player.SubtitleResolver;
import com.abhinavxt.debforge.player.UpNext;
import com.abhinavxt.debforge.ui.AppViewModel;
import com.abhinavxt.debforge.ui.AppViewModel_HiltModules;
import com.abhinavxt.debforge.ui.AppViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.AppViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.AuthGateViewModel;
import com.abhinavxt.debforge.ui.AuthGateViewModel_HiltModules;
import com.abhinavxt.debforge.ui.AuthGateViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.AuthGateViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel;
import com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel_HiltModules;
import com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.add.AddViewModel;
import com.abhinavxt.debforge.ui.add.AddViewModel_HiltModules;
import com.abhinavxt.debforge.ui.add.AddViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.add.AddViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.add.PendingAddStore;
import com.abhinavxt.debforge.ui.add.ProviderEvents;
import com.abhinavxt.debforge.ui.browse.BrowseViewModel;
import com.abhinavxt.debforge.ui.browse.BrowseViewModel_HiltModules;
import com.abhinavxt.debforge.ui.browse.BrowseViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.browse.BrowseViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.browse.ClipboardOfferViewModel;
import com.abhinavxt.debforge.ui.browse.ClipboardOfferViewModel_HiltModules;
import com.abhinavxt.debforge.ui.browse.ClipboardOfferViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.browse.ClipboardOfferViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.posters.PosterSetupViewModel;
import com.abhinavxt.debforge.ui.posters.PosterSetupViewModel_HiltModules;
import com.abhinavxt.debforge.ui.posters.PosterSetupViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.posters.PosterSetupViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.BackupViewModel;
import com.abhinavxt.debforge.ui.settings.BackupViewModel_HiltModules;
import com.abhinavxt.debforge.ui.settings.BackupViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.BackupViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.DataUsageViewModel;
import com.abhinavxt.debforge.ui.settings.DataUsageViewModel_HiltModules;
import com.abhinavxt.debforge.ui.settings.DataUsageViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.DataUsageViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.SettingsViewModel;
import com.abhinavxt.debforge.ui.settings.SettingsViewModel_HiltModules;
import com.abhinavxt.debforge.ui.settings.SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.settings.SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.setup.SetupViewModel;
import com.abhinavxt.debforge.ui.setup.SetupViewModel_HiltModules;
import com.abhinavxt.debforge.ui.setup.SetupViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.setup.SetupViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.ui.update.UpdateViewModel;
import com.abhinavxt.debforge.ui.update.UpdateViewModel_HiltModules;
import com.abhinavxt.debforge.ui.update.UpdateViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.abhinavxt.debforge.ui.update.UpdateViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.abhinavxt.debforge.widget.DownloadsTileService;
import com.abhinavxt.debforge.widget.DownloadsTileService_MembersInjector;
import com.abhinavxt.debforge.widget.DownloadsWidget;
import com.abhinavxt.debforge.widget.DownloadsWidget_MembersInjector;
import com.abhinavxt.debforge.widget.WidgetSync;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class DaggerDebForgeApp_HiltComponents_SingletonC {
  private DaggerDebForgeApp_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public DebForgeApp_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements DebForgeApp_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements DebForgeApp_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements DebForgeApp_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements DebForgeApp_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements DebForgeApp_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements DebForgeApp_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements DebForgeApp_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public DebForgeApp_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends DebForgeApp_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends DebForgeApp_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    FragmentCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }
  }

  private static final class ViewCImpl extends DebForgeApp_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends DebForgeApp_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    ActivityCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    ImmutableMap keySetMapOfClassOfAndBooleanBuilder() {
      ImmutableMap.Builder mapBuilder = ImmutableMap.<String, Boolean>builderWithExpectedSize(12);
      mapBuilder.put(ActiveDownloadsViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, ActiveDownloadsViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(AddViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, AddViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(AppViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, AppViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(AuthGateViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, AuthGateViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(BackupViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, BackupViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(BrowseViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, BrowseViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(ClipboardOfferViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, ClipboardOfferViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(DataUsageViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, DataUsageViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(PosterSetupViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, PosterSetupViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, SettingsViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(SetupViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, SetupViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(UpdateViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, UpdateViewModel_HiltModules.KeyModule.provide());
      return mapBuilder.build();
    }

    @Override
    public void injectHiltTestActivity(HiltTestActivity hiltTestActivity) {
    }

    @Override
    public void injectMainActivity(MainActivity mainActivity) {
      injectMainActivity2(mainActivity);
    }

    @Override
    public void injectPlayerActivity(PlayerActivity playerActivity) {
      injectPlayerActivity2(playerActivity);
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(keySetMapOfClassOfAndBooleanBuilder());
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    private MainActivity injectMainActivity2(MainActivity instance) {
      MainActivity_MembersInjector.injectPendingAdds(instance, singletonCImpl.pendingAddStoreProvider.get());
      MainActivity_MembersInjector.injectDownloadScheduler(instance, singletonCImpl.downloadSchedulerProvider.get());
      MainActivity_MembersInjector.injectDownloadController(instance, singletonCImpl.downloadControllerProvider.get());
      MainActivity_MembersInjector.injectHousekeeper(instance, singletonCImpl.housekeeperProvider.get());
      return instance;
    }

    private PlayerActivity injectPlayerActivity2(PlayerActivity instance2) {
      PlayerActivity_MembersInjector.injectPositions(instance2, singletonCImpl.playbackPositionsProvider.get());
      PlayerActivity_MembersInjector.injectUpNext(instance2, singletonCImpl.upNextProvider.get());
      PlayerActivity_MembersInjector.injectSubtitleResolver(instance2, singletonCImpl.subtitleResolverProvider.get());
      PlayerActivity_MembersInjector.injectRepository(instance2, singletonCImpl.downloadsRepositoryProvider.get());
      PlayerActivity_MembersInjector.injectSettings(instance2, singletonCImpl.settingsStoreProvider.get());
      return instance2;
    }
  }

  private static final class ViewModelCImpl extends DebForgeApp_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    Provider<ActiveDownloadsViewModel> activeDownloadsViewModelProvider;

    Provider<AddViewModel> addViewModelProvider;

    Provider<AppViewModel> appViewModelProvider;

    Provider<AuthGateViewModel> authGateViewModelProvider;

    Provider<BackupViewModel> backupViewModelProvider;

    Provider<BrowseViewModel> browseViewModelProvider;

    Provider<ClipboardOfferViewModel> clipboardOfferViewModelProvider;

    Provider<DataUsageViewModel> dataUsageViewModelProvider;

    Provider<PosterSetupViewModel> posterSetupViewModelProvider;

    Provider<SettingsViewModel> settingsViewModelProvider;

    Provider<SetupViewModel> setupViewModelProvider;

    Provider<UpdateViewModel> updateViewModelProvider;

    ViewModelCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        SavedStateHandle savedStateHandleParam, ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    ImmutableMap hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder() {
      ImmutableMap.Builder mapBuilder = ImmutableMap.<String, javax.inject.Provider<ViewModel>>builderWithExpectedSize(12);
      mapBuilder.put(ActiveDownloadsViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (activeDownloadsViewModelProvider)));
      mapBuilder.put(AddViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (addViewModelProvider)));
      mapBuilder.put(AppViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (appViewModelProvider)));
      mapBuilder.put(AuthGateViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (authGateViewModelProvider)));
      mapBuilder.put(BackupViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (backupViewModelProvider)));
      mapBuilder.put(BrowseViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (browseViewModelProvider)));
      mapBuilder.put(ClipboardOfferViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (clipboardOfferViewModelProvider)));
      mapBuilder.put(DataUsageViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (dataUsageViewModelProvider)));
      mapBuilder.put(PosterSetupViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (posterSetupViewModelProvider)));
      mapBuilder.put(SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (settingsViewModelProvider)));
      mapBuilder.put(SetupViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (setupViewModelProvider)));
      mapBuilder.put(UpdateViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (updateViewModelProvider)));
      return mapBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.activeDownloadsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.addViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.appViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.authGateViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
      this.backupViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 4);
      this.browseViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 5);
      this.clipboardOfferViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 6);
      this.dataUsageViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 7);
      this.posterSetupViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 8);
      this.settingsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 9);
      this.setupViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 10);
      this.updateViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 11);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder());
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return ImmutableMap.<Class<?>, Object>of();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // com.abhinavxt.debforge.ui.active.ActiveDownloadsViewModel
          return (T) new ActiveDownloadsViewModel(singletonCImpl.downloadDao(), singletonCImpl.downloadProgressTrackerProvider.get(), singletonCImpl.downloadControllerProvider.get(), singletonCImpl.downloadConditionsProvider.get());

          case 1: // com.abhinavxt.debforge.ui.add.AddViewModel
          return (T) new AddViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.authRepositoryProvider.get(), singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.pendingAddStoreProvider.get(), singletonCImpl.providerEventsProvider.get(), singletonCImpl.downloadControllerProvider.get(), singletonCImpl.readyWatcherProvider.get(), singletonCImpl.settingsStoreProvider.get());

          case 2: // com.abhinavxt.debforge.ui.AppViewModel
          return (T) new AppViewModel(singletonCImpl.settingsStoreProvider.get());

          case 3: // com.abhinavxt.debforge.ui.AuthGateViewModel
          return (T) new AuthGateViewModel(singletonCImpl.authRepositoryProvider.get());

          case 4: // com.abhinavxt.debforge.ui.settings.BackupViewModel
          return (T) new BackupViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.backupManagerProvider.get(), singletonCImpl.authRepositoryProvider.get(), singletonCImpl.downloadSchedulerProvider.get());

          case 5: // com.abhinavxt.debforge.ui.browse.BrowseViewModel
          return (T) new BrowseViewModel(singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.downloadControllerProvider.get(), singletonCImpl.settingsStoreProvider.get(), singletonCImpl.authRepositoryProvider.get(), singletonCImpl.metadataRepositoryProvider.get(), singletonCImpl.readyWatcherProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.providerEventsProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.followStoreProvider.get(), singletonCImpl.followCheckerProvider.get(), singletonCImpl.playbackPositionsProvider.get(), singletonCImpl.subtitleResolverProvider.get(), singletonCImpl.upNextProvider.get());

          case 6: // com.abhinavxt.debforge.ui.browse.ClipboardOfferViewModel
          return (T) new ClipboardOfferViewModel(singletonCImpl.settingsStoreProvider.get());

          case 7: // com.abhinavxt.debforge.ui.settings.DataUsageViewModel
          return (T) new DataUsageViewModel(singletonCImpl.usageDao(), singletonCImpl.authRepositoryProvider.get());

          case 8: // com.abhinavxt.debforge.ui.posters.PosterSetupViewModel
          return (T) new PosterSetupViewModel(singletonCImpl.metadataRepositoryProvider.get(), singletonCImpl.settingsStoreProvider.get());

          case 9: // com.abhinavxt.debforge.ui.settings.SettingsViewModel
          return (T) new SettingsViewModel(singletonCImpl.settingsStoreProvider.get(), singletonCImpl.authRepositoryProvider.get(), singletonCImpl.metadataRepositoryProvider.get(), singletonCImpl.downloadSchedulerProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.housekeeperProvider.get(), singletonCImpl.followStoreProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 10: // com.abhinavxt.debforge.ui.setup.SetupViewModel
          return (T) new SetupViewModel(singletonCImpl.authRepositoryProvider.get());

          case 11: // com.abhinavxt.debforge.ui.update.UpdateViewModel
          return (T) new UpdateViewModel(singletonCImpl.updateCheckerProvider.get(), singletonCImpl.settingsStoreProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends DebForgeApp_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends DebForgeApp_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }

    @Override
    public void injectDownloadJobService(DownloadJobService downloadJobService) {
      injectDownloadJobService2(downloadJobService);
    }

    @Override
    public void injectDownloadService(DownloadService downloadService) {
      injectDownloadService2(downloadService);
    }

    @Override
    public void injectFollowJobService(FollowJobService followJobService) {
      injectFollowJobService2(followJobService);
    }

    @Override
    public void injectReadyWatchJobService(ReadyWatchJobService readyWatchJobService) {
      injectReadyWatchJobService2(readyWatchJobService);
    }

    @Override
    public void injectDownloadsTileService(DownloadsTileService downloadsTileService) {
      injectDownloadsTileService2(downloadsTileService);
    }

    private DownloadJobService injectDownloadJobService2(DownloadJobService instance) {
      DownloadJobService_MembersInjector.injectRunner(instance, singletonCImpl.downloadQueueRunnerProvider.get());
      DownloadJobService_MembersInjector.injectProgressTracker(instance, singletonCImpl.downloadProgressTrackerProvider.get());
      DownloadJobService_MembersInjector.injectScheduler(instance, singletonCImpl.downloadSchedulerProvider.get());
      return instance;
    }

    private DownloadService injectDownloadService2(DownloadService instance2) {
      DownloadService_MembersInjector.injectRunner(instance2, singletonCImpl.downloadQueueRunnerProvider.get());
      DownloadService_MembersInjector.injectProgressTracker(instance2, singletonCImpl.downloadProgressTrackerProvider.get());
      DownloadService_MembersInjector.injectScheduler(instance2, singletonCImpl.downloadSchedulerProvider.get());
      return instance2;
    }

    private FollowJobService injectFollowJobService2(FollowJobService instance3) {
      FollowJobService_MembersInjector.injectChecker(instance3, singletonCImpl.followCheckerProvider.get());
      return instance3;
    }

    private ReadyWatchJobService injectReadyWatchJobService2(ReadyWatchJobService instance4) {
      ReadyWatchJobService_MembersInjector.injectWatcher(instance4, singletonCImpl.readyWatcherProvider.get());
      return instance4;
    }

    private DownloadsTileService injectDownloadsTileService2(DownloadsTileService instance5) {
      DownloadsTileService_MembersInjector.injectDownloadDao(instance5, singletonCImpl.downloadDao());
      DownloadsTileService_MembersInjector.injectController(instance5, singletonCImpl.downloadControllerProvider.get());
      return instance5;
    }
  }

  private static final class SingletonCImpl extends DebForgeApp_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    Provider<Context> provideContextProvider;

    Provider<TokenStore> tokenStoreProvider;

    Provider<DebForgeDatabase> provideDatabaseProvider;

    Provider<WidgetSync> widgetSyncProvider;

    Provider<FollowStore> followStoreProvider;

    Provider<OkHttpClient> provideDownloadOkHttpProvider;

    Provider<OkHttpClient> provideApiOkHttpProvider;

    Provider<Moshi> provideMoshiProvider;

    Provider<RealDebridOAuthApi> provideRealDebridOAuthApiProvider;

    Provider<RealDebridApi> provideRealDebridApiProvider;

    Provider<RealDebridProvider> realDebridProvider;

    Provider<TorBoxApi> provideTorBoxApiProvider;

    Provider<TorBoxProvider> torBoxProvider;

    Provider<AllDebridApi> provideAllDebridApiProvider;

    Provider<AllDebridProvider> allDebridProvider;

    Provider<PremiumizeApi> providePremiumizeApiProvider;

    Provider<PremiumizeProvider> premiumizeProvider;

    Provider<DebridLinkApi> provideDebridLinkApiProvider;

    Provider<DebridLinkProvider> debridLinkProvider;

    Provider<ProviderRegistry> providerRegistryProvider;

    Provider<DownloadsRepository> downloadsRepositoryProvider;

    Provider<DownloadProgressTracker> downloadProgressTrackerProvider;

    Provider<SettingsStore> settingsStoreProvider;

    Provider<NetworkMonitor> networkMonitorProvider;

    Provider<PowerMonitor> powerMonitorProvider;

    Provider<DownloadConditions> downloadConditionsProvider;

    Provider<RateLimiter> rateLimiterProvider;

    Provider<UsageRecorder> usageRecorderProvider;

    Provider<ChunkedDownloader> chunkedDownloaderProvider;

    Provider<CompletionNotifier> completionNotifierProvider;

    Provider<ServiceCleaner> serviceCleanerProvider;

    Provider<DownloadQueueRunner> downloadQueueRunnerProvider;

    Provider<DownloadScheduler> downloadSchedulerProvider;

    Provider<DownloadController> downloadControllerProvider;

    Provider<PendingAddStore> pendingAddStoreProvider;

    Provider<Housekeeper> housekeeperProvider;

    Provider<PlaybackPositions> playbackPositionsProvider;

    Provider<UpNext> upNextProvider;

    Provider<SubtitleResolver> subtitleResolverProvider;

    Provider<AuthRepository> authRepositoryProvider;

    Provider<ProviderEvents> providerEventsProvider;

    Provider<ReadyWatcher> readyWatcherProvider;

    Provider<BackupManager> backupManagerProvider;

    Provider<TmdbApi> provideTmdbApiProvider;

    Provider<MetadataRepository> metadataRepositoryProvider;

    Provider<FollowChecker> followCheckerProvider;

    Provider<UpdateChecker> updateCheckerProvider;

    SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);
      initialize2(applicationContextModuleParam);

    }

    DownloadDao downloadDao() {
      return DatabaseModule_ProvideDownloadDaoFactory.provideDownloadDao(provideDatabaseProvider.get());
    }

    ChunkDao chunkDao() {
      return DatabaseModule_ProvideChunkDaoFactory.provideChunkDao(provideDatabaseProvider.get());
    }

    Set<DebridProvider> setOfDebridProvider() {
      return ImmutableSet.<DebridProvider>of(realDebridProvider.get(), torBoxProvider.get(), allDebridProvider.get(), premiumizeProvider.get(), debridLinkProvider.get());
    }

    UsageDao usageDao() {
      return DatabaseModule_ProvideUsageDaoFactory.provideUsageDao(provideDatabaseProvider.get());
    }

    PlaybackDao playbackDao() {
      return DatabaseModule_ProvidePlaybackDaoFactory.providePlaybackDao(provideDatabaseProvider.get());
    }

    WatchDao watchDao() {
      return DatabaseModule_ProvideWatchDaoFactory.provideWatchDao(provideDatabaseProvider.get());
    }

    MediaMetaDao mediaMetaDao() {
      return DatabaseModule_ProvideMediaMetaDaoFactory.provideMediaMetaDao(provideDatabaseProvider.get());
    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideContextProvider = DoubleCheck.provider(new SwitchingProvider<Context>(singletonCImpl, 1));
      this.tokenStoreProvider = DoubleCheck.provider(new SwitchingProvider<TokenStore>(singletonCImpl, 0));
      this.provideDatabaseProvider = DoubleCheck.provider(new SwitchingProvider<DebForgeDatabase>(singletonCImpl, 3));
      this.widgetSyncProvider = DoubleCheck.provider(new SwitchingProvider<WidgetSync>(singletonCImpl, 2));
      this.followStoreProvider = DoubleCheck.provider(new SwitchingProvider<FollowStore>(singletonCImpl, 4));
      this.provideDownloadOkHttpProvider = DoubleCheck.provider(new SwitchingProvider<OkHttpClient>(singletonCImpl, 8));
      this.provideApiOkHttpProvider = DoubleCheck.provider(new SwitchingProvider<OkHttpClient>(singletonCImpl, 13));
      this.provideMoshiProvider = DoubleCheck.provider(new SwitchingProvider<Moshi>(singletonCImpl, 14));
      this.provideRealDebridOAuthApiProvider = DoubleCheck.provider(new SwitchingProvider<RealDebridOAuthApi>(singletonCImpl, 15));
      this.provideRealDebridApiProvider = DoubleCheck.provider(new SwitchingProvider<RealDebridApi>(singletonCImpl, 12));
      this.realDebridProvider = DoubleCheck.provider(new SwitchingProvider<RealDebridProvider>(singletonCImpl, 11));
      this.provideTorBoxApiProvider = DoubleCheck.provider(new SwitchingProvider<TorBoxApi>(singletonCImpl, 17));
      this.torBoxProvider = DoubleCheck.provider(new SwitchingProvider<TorBoxProvider>(singletonCImpl, 16));
      this.provideAllDebridApiProvider = DoubleCheck.provider(new SwitchingProvider<AllDebridApi>(singletonCImpl, 19));
      this.allDebridProvider = DoubleCheck.provider(new SwitchingProvider<AllDebridProvider>(singletonCImpl, 18));
      this.providePremiumizeApiProvider = DoubleCheck.provider(new SwitchingProvider<PremiumizeApi>(singletonCImpl, 21));
      this.premiumizeProvider = DoubleCheck.provider(new SwitchingProvider<PremiumizeProvider>(singletonCImpl, 20));
      this.provideDebridLinkApiProvider = DoubleCheck.provider(new SwitchingProvider<DebridLinkApi>(singletonCImpl, 23));
      this.debridLinkProvider = DoubleCheck.provider(new SwitchingProvider<DebridLinkProvider>(singletonCImpl, 22));
      this.providerRegistryProvider = DoubleCheck.provider(new SwitchingProvider<ProviderRegistry>(singletonCImpl, 10));
      this.downloadsRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<DownloadsRepository>(singletonCImpl, 9));
      this.downloadProgressTrackerProvider = DoubleCheck.provider(new SwitchingProvider<DownloadProgressTracker>(singletonCImpl, 24));
      this.settingsStoreProvider = DoubleCheck.provider(new SwitchingProvider<SettingsStore>(singletonCImpl, 26));
      this.networkMonitorProvider = DoubleCheck.provider(new SwitchingProvider<NetworkMonitor>(singletonCImpl, 27));
      this.powerMonitorProvider = DoubleCheck.provider(new SwitchingProvider<PowerMonitor>(singletonCImpl, 28));
    }

    @SuppressWarnings("unchecked")
    private void initialize2(final ApplicationContextModule applicationContextModuleParam) {
      this.downloadConditionsProvider = DoubleCheck.provider(new SwitchingProvider<DownloadConditions>(singletonCImpl, 25));
      this.rateLimiterProvider = DoubleCheck.provider(new SwitchingProvider<RateLimiter>(singletonCImpl, 29));
      this.usageRecorderProvider = DoubleCheck.provider(new SwitchingProvider<UsageRecorder>(singletonCImpl, 30));
      this.chunkedDownloaderProvider = DoubleCheck.provider(new SwitchingProvider<ChunkedDownloader>(singletonCImpl, 7));
      this.completionNotifierProvider = DoubleCheck.provider(new SwitchingProvider<CompletionNotifier>(singletonCImpl, 31));
      this.serviceCleanerProvider = DoubleCheck.provider(new SwitchingProvider<ServiceCleaner>(singletonCImpl, 32));
      this.downloadQueueRunnerProvider = DoubleCheck.provider(new SwitchingProvider<DownloadQueueRunner>(singletonCImpl, 6));
      this.downloadSchedulerProvider = DoubleCheck.provider(new SwitchingProvider<DownloadScheduler>(singletonCImpl, 5));
      this.downloadControllerProvider = DoubleCheck.provider(new SwitchingProvider<DownloadController>(singletonCImpl, 33));
      this.pendingAddStoreProvider = DoubleCheck.provider(new SwitchingProvider<PendingAddStore>(singletonCImpl, 34));
      this.housekeeperProvider = DoubleCheck.provider(new SwitchingProvider<Housekeeper>(singletonCImpl, 35));
      this.playbackPositionsProvider = DoubleCheck.provider(new SwitchingProvider<PlaybackPositions>(singletonCImpl, 36));
      this.upNextProvider = DoubleCheck.provider(new SwitchingProvider<UpNext>(singletonCImpl, 37));
      this.subtitleResolverProvider = DoubleCheck.provider(new SwitchingProvider<SubtitleResolver>(singletonCImpl, 38));
      this.authRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<AuthRepository>(singletonCImpl, 39));
      this.providerEventsProvider = DoubleCheck.provider(new SwitchingProvider<ProviderEvents>(singletonCImpl, 40));
      this.readyWatcherProvider = DoubleCheck.provider(new SwitchingProvider<ReadyWatcher>(singletonCImpl, 41));
      this.backupManagerProvider = DoubleCheck.provider(new SwitchingProvider<BackupManager>(singletonCImpl, 42));
      this.provideTmdbApiProvider = DoubleCheck.provider(new SwitchingProvider<TmdbApi>(singletonCImpl, 44));
      this.metadataRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<MetadataRepository>(singletonCImpl, 43));
      this.followCheckerProvider = DoubleCheck.provider(new SwitchingProvider<FollowChecker>(singletonCImpl, 45));
      this.updateCheckerProvider = DoubleCheck.provider(new SwitchingProvider<UpdateChecker>(singletonCImpl, 46));
    }

    @Override
    public void injectDebForgeApp(DebForgeApp debForgeApp) {
      injectDebForgeApp2(debForgeApp);
    }

    @Override
    public void injectScheduleAlarmReceiver(ScheduleAlarmReceiver scheduleAlarmReceiver) {
      injectScheduleAlarmReceiver2(scheduleAlarmReceiver);
    }

    @Override
    public void injectScheduleRearmReceiver(ScheduleRearmReceiver scheduleRearmReceiver) {
      injectScheduleRearmReceiver2(scheduleRearmReceiver);
    }

    @Override
    public void injectDownloadsWidget(DownloadsWidget downloadsWidget) {
      injectDownloadsWidget2(downloadsWidget);
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return ImmutableSet.<Boolean>of();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    private DebForgeApp injectDebForgeApp2(DebForgeApp instance) {
      DebForgeApp_MembersInjector.injectTokenStore(instance, tokenStoreProvider.get());
      DebForgeApp_MembersInjector.injectWidgetSync(instance, widgetSyncProvider.get());
      DebForgeApp_MembersInjector.injectFollowStore(instance, followStoreProvider.get());
      return instance;
    }

    private ScheduleAlarmReceiver injectScheduleAlarmReceiver2(ScheduleAlarmReceiver instance2) {
      ScheduleAlarmReceiver_MembersInjector.injectScheduler(instance2, downloadSchedulerProvider.get());
      return instance2;
    }

    private ScheduleRearmReceiver injectScheduleRearmReceiver2(ScheduleRearmReceiver instance3) {
      ScheduleRearmReceiver_MembersInjector.injectScheduler(instance3, downloadSchedulerProvider.get());
      return instance3;
    }

    private DownloadsWidget injectDownloadsWidget2(DownloadsWidget instance4) {
      DownloadsWidget_MembersInjector.injectDownloadDao(instance4, downloadDao());
      DownloadsWidget_MembersInjector.injectController(instance4, downloadControllerProvider.get());
      return instance4;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // com.abhinavxt.debforge.data.prefs.TokenStore
          return (T) new TokenStore(singletonCImpl.provideContextProvider.get());

          case 1: // android.content.Context
          return (T) AppModule_ProvideContextFactory.provideContext(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 2: // com.abhinavxt.debforge.widget.WidgetSync
          return (T) new WidgetSync(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.downloadDao());

          case 3: // com.abhinavxt.debforge.data.local.DebForgeDatabase
          return (T) DatabaseModule_ProvideDatabaseFactory.provideDatabase(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 4: // com.abhinavxt.debforge.data.follow.FollowStore
          return (T) new FollowStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 5: // com.abhinavxt.debforge.download.DownloadScheduler
          return (T) new DownloadScheduler(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.downloadQueueRunnerProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.downloadConditionsProvider.get());

          case 6: // com.abhinavxt.debforge.download.DownloadQueueRunner
          return (T) new DownloadQueueRunner(singletonCImpl.chunkedDownloaderProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.chunkDao(), singletonCImpl.downloadProgressTrackerProvider.get(), singletonCImpl.downloadConditionsProvider.get(), singletonCImpl.completionNotifierProvider.get(), singletonCImpl.serviceCleanerProvider.get());

          case 7: // com.abhinavxt.debforge.download.ChunkedDownloader
          return (T) new ChunkedDownloader(singletonCImpl.provideDownloadOkHttpProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.chunkDao(), singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.downloadProgressTrackerProvider.get(), singletonCImpl.downloadConditionsProvider.get(), singletonCImpl.rateLimiterProvider.get(), singletonCImpl.usageRecorderProvider.get());

          case 8: // @com.abhinavxt.debforge.di.DownloadHttpClient okhttp3.OkHttpClient
          return (T) NetworkModule_ProvideDownloadOkHttpFactory.provideDownloadOkHttp();

          case 9: // com.abhinavxt.debforge.data.repository.DownloadsRepository
          return (T) new DownloadsRepository(singletonCImpl.providerRegistryProvider.get());

          case 10: // com.abhinavxt.debforge.data.provider.ProviderRegistry
          return (T) new ProviderRegistry(singletonCImpl.setOfDebridProvider());

          case 11: // com.abhinavxt.debforge.data.provider.realdebrid.RealDebridProvider
          return (T) new RealDebridProvider(singletonCImpl.provideRealDebridApiProvider.get(), singletonCImpl.provideRealDebridOAuthApiProvider.get());

          case 12: // com.abhinavxt.debforge.data.provider.realdebrid.RealDebridApi
          return (T) ProviderModule_ProvideRealDebridApiFactory.provideRealDebridApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.tokenStoreProvider.get(), singletonCImpl.provideRealDebridOAuthApiProvider.get());

          case 13: // @com.abhinavxt.debforge.di.ApiHttpClient okhttp3.OkHttpClient
          return (T) NetworkModule_ProvideApiOkHttpFactory.provideApiOkHttp();

          case 14: // com.squareup.moshi.Moshi
          return (T) NetworkModule_ProvideMoshiFactory.provideMoshi();

          case 15: // com.abhinavxt.debforge.data.provider.realdebrid.RealDebridOAuthApi
          return (T) ProviderModule_ProvideRealDebridOAuthApiFactory.provideRealDebridOAuthApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get());

          case 16: // com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider
          return (T) new TorBoxProvider(singletonCImpl.provideTorBoxApiProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 17: // com.abhinavxt.debforge.data.provider.torbox.TorBoxApi
          return (T) ProviderModule_ProvideTorBoxApiFactory.provideTorBoxApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 18: // com.abhinavxt.debforge.data.provider.alldebrid.AllDebridProvider
          return (T) new AllDebridProvider(singletonCImpl.provideAllDebridApiProvider.get());

          case 19: // com.abhinavxt.debforge.data.provider.alldebrid.AllDebridApi
          return (T) ProviderModule_ProvideAllDebridApiFactory.provideAllDebridApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 20: // com.abhinavxt.debforge.data.provider.premiumize.PremiumizeProvider
          return (T) new PremiumizeProvider(singletonCImpl.providePremiumizeApiProvider.get());

          case 21: // com.abhinavxt.debforge.data.provider.premiumize.PremiumizeApi
          return (T) ProviderModule_ProvidePremiumizeApiFactory.providePremiumizeApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 22: // com.abhinavxt.debforge.data.provider.debridlink.DebridLinkProvider
          return (T) new DebridLinkProvider(singletonCImpl.provideDebridLinkApiProvider.get());

          case 23: // com.abhinavxt.debforge.data.provider.debridlink.DebridLinkApi
          return (T) ProviderModule_ProvideDebridLinkApiFactory.provideDebridLinkApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 24: // com.abhinavxt.debforge.download.DownloadProgressTracker
          return (T) new DownloadProgressTracker();

          case 25: // com.abhinavxt.debforge.download.conditions.DownloadConditions
          return (T) new DownloadConditions(singletonCImpl.settingsStoreProvider.get(), singletonCImpl.networkMonitorProvider.get(), singletonCImpl.powerMonitorProvider.get());

          case 26: // com.abhinavxt.debforge.data.prefs.SettingsStore
          return (T) new SettingsStore(singletonCImpl.provideContextProvider.get());

          case 27: // com.abhinavxt.debforge.download.NetworkMonitor
          return (T) new NetworkMonitor(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 28: // com.abhinavxt.debforge.download.conditions.PowerMonitor
          return (T) new PowerMonitor(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 29: // com.abhinavxt.debforge.download.conditions.RateLimiter
          return (T) new RateLimiter(singletonCImpl.downloadConditionsProvider.get());

          case 30: // com.abhinavxt.debforge.data.usage.UsageRecorder
          return (T) new UsageRecorder(singletonCImpl.usageDao(), singletonCImpl.networkMonitorProvider.get());

          case 31: // com.abhinavxt.debforge.download.CompletionNotifier
          return (T) new CompletionNotifier(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 32: // com.abhinavxt.debforge.download.ServiceCleaner
          return (T) new ServiceCleaner(singletonCImpl.settingsStoreProvider.get(), singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.downloadDao());

          case 33: // com.abhinavxt.debforge.download.DownloadController
          return (T) new DownloadController(singletonCImpl.downloadDao(), singletonCImpl.settingsStoreProvider.get(), singletonCImpl.downloadQueueRunnerProvider.get(), singletonCImpl.downloadSchedulerProvider.get());

          case 34: // com.abhinavxt.debforge.ui.add.PendingAddStore
          return (T) new PendingAddStore();

          case 35: // com.abhinavxt.debforge.download.Housekeeper
          return (T) new Housekeeper(singletonCImpl.settingsStoreProvider.get(), singletonCImpl.downloadDao());

          case 36: // com.abhinavxt.debforge.data.playback.PlaybackPositions
          return (T) new PlaybackPositions(singletonCImpl.playbackDao());

          case 37: // com.abhinavxt.debforge.player.UpNext
          return (T) new UpNext();

          case 38: // com.abhinavxt.debforge.player.SubtitleResolver
          return (T) new SubtitleResolver(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.downloadsRepositoryProvider.get());

          case 39: // com.abhinavxt.debforge.data.repository.AuthRepository
          return (T) new AuthRepository(singletonCImpl.providerRegistryProvider.get(), singletonCImpl.tokenStoreProvider.get());

          case 40: // com.abhinavxt.debforge.ui.add.ProviderEvents
          return (T) new ProviderEvents();

          case 41: // com.abhinavxt.debforge.download.watch.ReadyWatcher
          return (T) new ReadyWatcher(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.watchDao(), singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.downloadControllerProvider.get(), singletonCImpl.settingsStoreProvider.get());

          case 42: // com.abhinavxt.debforge.data.backup.BackupManager
          return (T) new BackupManager(singletonCImpl.settingsStoreProvider.get(), singletonCImpl.tokenStoreProvider.get(), singletonCImpl.followStoreProvider.get(), singletonCImpl.provideMoshiProvider.get());

          case 43: // com.abhinavxt.debforge.data.metadata.MetadataRepository
          return (T) new MetadataRepository(singletonCImpl.provideTmdbApiProvider.get(), singletonCImpl.mediaMetaDao(), singletonCImpl.settingsStoreProvider.get());

          case 44: // com.abhinavxt.debforge.data.metadata.TmdbApi
          return (T) MetadataModule_ProvideTmdbApiFactory.provideTmdbApi(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.settingsStoreProvider.get());

          case 45: // com.abhinavxt.debforge.download.follow.FollowChecker
          return (T) new FollowChecker(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.followStoreProvider.get(), singletonCImpl.downloadsRepositoryProvider.get(), singletonCImpl.downloadControllerProvider.get(), singletonCImpl.downloadDao(), singletonCImpl.settingsStoreProvider.get());

          case 46: // com.abhinavxt.debforge.data.update.UpdateChecker
          return (T) new UpdateChecker(singletonCImpl.provideApiOkHttpProvider.get(), singletonCImpl.provideMoshiProvider.get(), singletonCImpl.settingsStoreProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
