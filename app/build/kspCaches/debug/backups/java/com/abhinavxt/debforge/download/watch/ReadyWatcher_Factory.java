package com.abhinavxt.debforge.download.watch;

import android.content.Context;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
import com.abhinavxt.debforge.download.DownloadController;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class ReadyWatcher_Factory implements Factory<ReadyWatcher> {
  private final Provider<Context> contextProvider;

  private final Provider<WatchDao> daoProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private final Provider<DownloadController> controllerProvider;

  private final Provider<SettingsStore> settingsProvider;

  private ReadyWatcher_Factory(Provider<Context> contextProvider, Provider<WatchDao> daoProvider,
      Provider<DownloadsRepository> repositoryProvider,
      Provider<DownloadController> controllerProvider, Provider<SettingsStore> settingsProvider) {
    this.contextProvider = contextProvider;
    this.daoProvider = daoProvider;
    this.repositoryProvider = repositoryProvider;
    this.controllerProvider = controllerProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public ReadyWatcher get() {
    return newInstance(contextProvider.get(), daoProvider.get(), repositoryProvider.get(), controllerProvider.get(), settingsProvider.get());
  }

  public static ReadyWatcher_Factory create(Provider<Context> contextProvider,
      Provider<WatchDao> daoProvider, Provider<DownloadsRepository> repositoryProvider,
      Provider<DownloadController> controllerProvider, Provider<SettingsStore> settingsProvider) {
    return new ReadyWatcher_Factory(contextProvider, daoProvider, repositoryProvider, controllerProvider, settingsProvider);
  }

  public static ReadyWatcher newInstance(Context context, WatchDao dao,
      DownloadsRepository repository, DownloadController controller, SettingsStore settings) {
    return new ReadyWatcher(context, dao, repository, controller, settings);
  }
}
