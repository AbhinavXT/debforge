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
public final class Housekeeper_Factory implements Factory<Housekeeper> {
  private final Provider<SettingsStore> settingsProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private Housekeeper_Factory(Provider<SettingsStore> settingsProvider,
      Provider<DownloadDao> downloadDaoProvider) {
    this.settingsProvider = settingsProvider;
    this.downloadDaoProvider = downloadDaoProvider;
  }

  @Override
  public Housekeeper get() {
    return newInstance(settingsProvider.get(), downloadDaoProvider.get());
  }

  public static Housekeeper_Factory create(Provider<SettingsStore> settingsProvider,
      Provider<DownloadDao> downloadDaoProvider) {
    return new Housekeeper_Factory(settingsProvider, downloadDaoProvider);
  }

  public static Housekeeper newInstance(SettingsStore settings, DownloadDao downloadDao) {
    return new Housekeeper(settings, downloadDao);
  }
}
