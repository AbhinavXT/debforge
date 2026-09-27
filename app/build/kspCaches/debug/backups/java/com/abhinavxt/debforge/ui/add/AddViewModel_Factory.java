package com.abhinavxt.debforge.ui.add;

import android.content.Context;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
import com.abhinavxt.debforge.download.DownloadController;
import com.abhinavxt.debforge.download.watch.ReadyWatcher;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class AddViewModel_Factory implements Factory<AddViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<AuthRepository> authProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private final Provider<PendingAddStore> pendingAddsProvider;

  private final Provider<ProviderEvents> eventsProvider;

  private final Provider<DownloadController> controllerProvider;

  private final Provider<ReadyWatcher> watcherProvider;

  private final Provider<SettingsStore> settingsProvider;

  private AddViewModel_Factory(Provider<Context> contextProvider,
      Provider<AuthRepository> authProvider, Provider<DownloadsRepository> repositoryProvider,
      Provider<PendingAddStore> pendingAddsProvider, Provider<ProviderEvents> eventsProvider,
      Provider<DownloadController> controllerProvider, Provider<ReadyWatcher> watcherProvider,
      Provider<SettingsStore> settingsProvider) {
    this.contextProvider = contextProvider;
    this.authProvider = authProvider;
    this.repositoryProvider = repositoryProvider;
    this.pendingAddsProvider = pendingAddsProvider;
    this.eventsProvider = eventsProvider;
    this.controllerProvider = controllerProvider;
    this.watcherProvider = watcherProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public AddViewModel get() {
    return newInstance(contextProvider.get(), authProvider.get(), repositoryProvider.get(), pendingAddsProvider.get(), eventsProvider.get(), controllerProvider.get(), watcherProvider.get(), settingsProvider.get());
  }

  public static AddViewModel_Factory create(Provider<Context> contextProvider,
      Provider<AuthRepository> authProvider, Provider<DownloadsRepository> repositoryProvider,
      Provider<PendingAddStore> pendingAddsProvider, Provider<ProviderEvents> eventsProvider,
      Provider<DownloadController> controllerProvider, Provider<ReadyWatcher> watcherProvider,
      Provider<SettingsStore> settingsProvider) {
    return new AddViewModel_Factory(contextProvider, authProvider, repositoryProvider, pendingAddsProvider, eventsProvider, controllerProvider, watcherProvider, settingsProvider);
  }

  public static AddViewModel newInstance(Context context, AuthRepository auth,
      DownloadsRepository repository, PendingAddStore pendingAdds, ProviderEvents events,
      DownloadController controller, ReadyWatcher watcher, SettingsStore settings) {
    return new AddViewModel(context, auth, repository, pendingAdds, events, controller, watcher, settings);
  }
}
