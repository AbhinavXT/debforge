package com.abhinavxt.debforge.ui.update;

import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.update.UpdateChecker;
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
public final class UpdateViewModel_Factory implements Factory<UpdateViewModel> {
  private final Provider<UpdateChecker> checkerProvider;

  private final Provider<SettingsStore> settingsProvider;

  private UpdateViewModel_Factory(Provider<UpdateChecker> checkerProvider,
      Provider<SettingsStore> settingsProvider) {
    this.checkerProvider = checkerProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public UpdateViewModel get() {
    return newInstance(checkerProvider.get(), settingsProvider.get());
  }

  public static UpdateViewModel_Factory create(Provider<UpdateChecker> checkerProvider,
      Provider<SettingsStore> settingsProvider) {
    return new UpdateViewModel_Factory(checkerProvider, settingsProvider);
  }

  public static UpdateViewModel newInstance(UpdateChecker checker, SettingsStore settings) {
    return new UpdateViewModel(checker, settings);
  }
}
