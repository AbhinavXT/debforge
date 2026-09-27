package com.abhinavxt.debforge.ui.browse;

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
public final class ClipboardOfferViewModel_Factory implements Factory<ClipboardOfferViewModel> {
  private final Provider<SettingsStore> settingsProvider;

  private ClipboardOfferViewModel_Factory(Provider<SettingsStore> settingsProvider) {
    this.settingsProvider = settingsProvider;
  }

  @Override
  public ClipboardOfferViewModel get() {
    return newInstance(settingsProvider.get());
  }

  public static ClipboardOfferViewModel_Factory create(Provider<SettingsStore> settingsProvider) {
    return new ClipboardOfferViewModel_Factory(settingsProvider);
  }

  public static ClipboardOfferViewModel newInstance(SettingsStore settings) {
    return new ClipboardOfferViewModel(settings);
  }
}
