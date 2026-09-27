package com.abhinavxt.debforge.ui.posters;

import com.abhinavxt.debforge.data.metadata.MetadataRepository;
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
public final class PosterSetupViewModel_Factory implements Factory<PosterSetupViewModel> {
  private final Provider<MetadataRepository> metadataProvider;

  private final Provider<SettingsStore> settingsProvider;

  private PosterSetupViewModel_Factory(Provider<MetadataRepository> metadataProvider,
      Provider<SettingsStore> settingsProvider) {
    this.metadataProvider = metadataProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public PosterSetupViewModel get() {
    return newInstance(metadataProvider.get(), settingsProvider.get());
  }

  public static PosterSetupViewModel_Factory create(Provider<MetadataRepository> metadataProvider,
      Provider<SettingsStore> settingsProvider) {
    return new PosterSetupViewModel_Factory(metadataProvider, settingsProvider);
  }

  public static PosterSetupViewModel newInstance(MetadataRepository metadata,
      SettingsStore settings) {
    return new PosterSetupViewModel(metadata, settings);
  }
}
