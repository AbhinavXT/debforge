package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.metadata.TmdbApi;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.squareup.moshi.Moshi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.abhinavxt.debforge.di.ApiHttpClient")
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
public final class MetadataModule_ProvideTmdbApiFactory implements Factory<TmdbApi> {
  private final Provider<OkHttpClient> baseProvider;

  private final Provider<Moshi> moshiProvider;

  private final Provider<SettingsStore> settingsProvider;

  private MetadataModule_ProvideTmdbApiFactory(Provider<OkHttpClient> baseProvider,
      Provider<Moshi> moshiProvider, Provider<SettingsStore> settingsProvider) {
    this.baseProvider = baseProvider;
    this.moshiProvider = moshiProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public TmdbApi get() {
    return provideTmdbApi(baseProvider.get(), moshiProvider.get(), settingsProvider.get());
  }

  public static MetadataModule_ProvideTmdbApiFactory create(Provider<OkHttpClient> baseProvider,
      Provider<Moshi> moshiProvider, Provider<SettingsStore> settingsProvider) {
    return new MetadataModule_ProvideTmdbApiFactory(baseProvider, moshiProvider, settingsProvider);
  }

  public static TmdbApi provideTmdbApi(OkHttpClient base, Moshi moshi, SettingsStore settings) {
    return Preconditions.checkNotNullFromProvides(MetadataModule.INSTANCE.provideTmdbApi(base, moshi, settings));
  }
}
