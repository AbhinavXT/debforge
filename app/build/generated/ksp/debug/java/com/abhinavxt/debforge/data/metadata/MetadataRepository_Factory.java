package com.abhinavxt.debforge.data.metadata;

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
public final class MetadataRepository_Factory implements Factory<MetadataRepository> {
  private final Provider<TmdbApi> apiProvider;

  private final Provider<MediaMetaDao> daoProvider;

  private final Provider<SettingsStore> settingsProvider;

  private MetadataRepository_Factory(Provider<TmdbApi> apiProvider,
      Provider<MediaMetaDao> daoProvider, Provider<SettingsStore> settingsProvider) {
    this.apiProvider = apiProvider;
    this.daoProvider = daoProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public MetadataRepository get() {
    return newInstance(apiProvider.get(), daoProvider.get(), settingsProvider.get());
  }

  public static MetadataRepository_Factory create(Provider<TmdbApi> apiProvider,
      Provider<MediaMetaDao> daoProvider, Provider<SettingsStore> settingsProvider) {
    return new MetadataRepository_Factory(apiProvider, daoProvider, settingsProvider);
  }

  public static MetadataRepository newInstance(TmdbApi api, MediaMetaDao dao,
      SettingsStore settings) {
    return new MetadataRepository(api, dao, settings);
  }
}
