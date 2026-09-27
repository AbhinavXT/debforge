package com.abhinavxt.debforge.download;

import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
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
public final class ServiceCleaner_Factory implements Factory<ServiceCleaner> {
  private final Provider<SettingsStore> settingsProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private ServiceCleaner_Factory(Provider<SettingsStore> settingsProvider,
      Provider<DownloadsRepository> repositoryProvider, Provider<DownloadDao> downloadDaoProvider) {
    this.settingsProvider = settingsProvider;
    this.repositoryProvider = repositoryProvider;
    this.downloadDaoProvider = downloadDaoProvider;
  }

  @Override
  public ServiceCleaner get() {
    return newInstance(settingsProvider.get(), repositoryProvider.get(), downloadDaoProvider.get());
  }

  public static ServiceCleaner_Factory create(Provider<SettingsStore> settingsProvider,
      Provider<DownloadsRepository> repositoryProvider, Provider<DownloadDao> downloadDaoProvider) {
    return new ServiceCleaner_Factory(settingsProvider, repositoryProvider, downloadDaoProvider);
  }

  public static ServiceCleaner newInstance(SettingsStore settings, DownloadsRepository repository,
      DownloadDao downloadDao) {
    return new ServiceCleaner(settings, repository, downloadDao);
  }
}
