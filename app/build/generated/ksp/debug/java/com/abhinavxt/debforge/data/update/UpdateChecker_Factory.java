package com.abhinavxt.debforge.data.update;

import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.squareup.moshi.Moshi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class UpdateChecker_Factory implements Factory<UpdateChecker> {
  private final Provider<OkHttpClient> clientProvider;

  private final Provider<Moshi> moshiProvider;

  private final Provider<SettingsStore> settingsProvider;

  private UpdateChecker_Factory(Provider<OkHttpClient> clientProvider,
      Provider<Moshi> moshiProvider, Provider<SettingsStore> settingsProvider) {
    this.clientProvider = clientProvider;
    this.moshiProvider = moshiProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public UpdateChecker get() {
    return newInstance(clientProvider.get(), moshiProvider.get(), settingsProvider.get());
  }

  public static UpdateChecker_Factory create(Provider<OkHttpClient> clientProvider,
      Provider<Moshi> moshiProvider, Provider<SettingsStore> settingsProvider) {
    return new UpdateChecker_Factory(clientProvider, moshiProvider, settingsProvider);
  }

  public static UpdateChecker newInstance(OkHttpClient client, Moshi moshi,
      SettingsStore settings) {
    return new UpdateChecker(client, moshi, settings);
  }
}
