package com.abhinavxt.debforge.download;

import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
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
public final class DownloadController_Factory implements Factory<DownloadController> {
  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<SettingsStore> settingsStoreProvider;

  private final Provider<DownloadQueueRunner> runnerProvider;

  private final Provider<DownloadScheduler> schedulerProvider;

  private DownloadController_Factory(Provider<DownloadDao> downloadDaoProvider,
      Provider<SettingsStore> settingsStoreProvider, Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    this.downloadDaoProvider = downloadDaoProvider;
    this.settingsStoreProvider = settingsStoreProvider;
    this.runnerProvider = runnerProvider;
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public DownloadController get() {
    return newInstance(downloadDaoProvider.get(), settingsStoreProvider.get(), runnerProvider.get(), schedulerProvider.get());
  }

  public static DownloadController_Factory create(Provider<DownloadDao> downloadDaoProvider,
      Provider<SettingsStore> settingsStoreProvider, Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    return new DownloadController_Factory(downloadDaoProvider, settingsStoreProvider, runnerProvider, schedulerProvider);
  }

  public static DownloadController newInstance(DownloadDao downloadDao, SettingsStore settingsStore,
      DownloadQueueRunner runner, DownloadScheduler scheduler) {
    return new DownloadController(downloadDao, settingsStore, runner, scheduler);
  }
}
