package com.abhinavxt.debforge.ui.settings;

import android.content.Context;
import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.metadata.MetadataRepository;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.download.DownloadScheduler;
import com.abhinavxt.debforge.download.Housekeeper;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<SettingsStore> settingsStoreProvider;

  private final Provider<AuthRepository> authProvider;

  private final Provider<MetadataRepository> metadataProvider;

  private final Provider<DownloadScheduler> downloadSchedulerProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<Housekeeper> housekeeperProvider;

  private final Provider<FollowStore> followStoreProvider;

  private final Provider<Context> appContextProvider;

  private SettingsViewModel_Factory(Provider<SettingsStore> settingsStoreProvider,
      Provider<AuthRepository> authProvider, Provider<MetadataRepository> metadataProvider,
      Provider<DownloadScheduler> downloadSchedulerProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<Housekeeper> housekeeperProvider,
      Provider<FollowStore> followStoreProvider, Provider<Context> appContextProvider) {
    this.settingsStoreProvider = settingsStoreProvider;
    this.authProvider = authProvider;
    this.metadataProvider = metadataProvider;
    this.downloadSchedulerProvider = downloadSchedulerProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.housekeeperProvider = housekeeperProvider;
    this.followStoreProvider = followStoreProvider;
    this.appContextProvider = appContextProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(settingsStoreProvider.get(), authProvider.get(), metadataProvider.get(), downloadSchedulerProvider.get(), downloadDaoProvider.get(), housekeeperProvider.get(), followStoreProvider.get(), appContextProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<SettingsStore> settingsStoreProvider,
      Provider<AuthRepository> authProvider, Provider<MetadataRepository> metadataProvider,
      Provider<DownloadScheduler> downloadSchedulerProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<Housekeeper> housekeeperProvider,
      Provider<FollowStore> followStoreProvider, Provider<Context> appContextProvider) {
    return new SettingsViewModel_Factory(settingsStoreProvider, authProvider, metadataProvider, downloadSchedulerProvider, downloadDaoProvider, housekeeperProvider, followStoreProvider, appContextProvider);
  }

  public static SettingsViewModel newInstance(SettingsStore settingsStore, AuthRepository auth,
      MetadataRepository metadata, DownloadScheduler downloadScheduler, DownloadDao downloadDao,
      Housekeeper housekeeper, FollowStore followStore, Context appContext) {
    return new SettingsViewModel(settingsStore, auth, metadata, downloadScheduler, downloadDao, housekeeper, followStore, appContext);
  }
}
