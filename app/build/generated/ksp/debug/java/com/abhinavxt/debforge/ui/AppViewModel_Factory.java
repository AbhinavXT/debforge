package com.abhinavxt.debforge.ui;

import com.abhinavxt.debforge.data.prefs.SettingsStore;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class AppViewModel_Factory implements Factory<AppViewModel> {
  private final Provider<SettingsStore> settingsStoreProvider;

  private AppViewModel_Factory(Provider<SettingsStore> settingsStoreProvider) {
    this.settingsStoreProvider = settingsStoreProvider;
  }

  @Override
  public AppViewModel get() {
    return newInstance(settingsStoreProvider.get());
  }

  public static AppViewModel_Factory create(Provider<SettingsStore> settingsStoreProvider) {
    return new AppViewModel_Factory(settingsStoreProvider);
  }

  public static AppViewModel newInstance(SettingsStore settingsStore) {
    return new AppViewModel(settingsStore);
  }
}
