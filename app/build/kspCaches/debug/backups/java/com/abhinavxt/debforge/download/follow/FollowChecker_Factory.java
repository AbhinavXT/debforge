package com.abhinavxt.debforge.download.follow;

import android.content.Context;
import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.local.DownloadDao;
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
public final class FollowChecker_Factory implements Factory<FollowChecker> {
  private final Provider<Context> contextProvider;

  private final Provider<FollowStore> storeProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private final Provider<DownloadController> controllerProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<SettingsStore> settingsProvider;

  private FollowChecker_Factory(Provider<Context> contextProvider,
      Provider<FollowStore> storeProvider, Provider<DownloadsRepository> repositoryProvider,
      Provider<DownloadController> controllerProvider, Provider<DownloadDao> downloadDaoProvider,
      Provider<SettingsStore> settingsProvider) {
    this.contextProvider = contextProvider;
    this.storeProvider = storeProvider;
    this.repositoryProvider = repositoryProvider;
    this.controllerProvider = controllerProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public FollowChecker get() {
    return newInstance(contextProvider.get(), storeProvider.get(), repositoryProvider.get(), controllerProvider.get(), downloadDaoProvider.get(), settingsProvider.get());
  }

  public static FollowChecker_Factory create(Provider<Context> contextProvider,
      Provider<FollowStore> storeProvider, Provider<DownloadsRepository> repositoryProvider,
      Provider<DownloadController> controllerProvider, Provider<DownloadDao> downloadDaoProvider,
      Provider<SettingsStore> settingsProvider) {
    return new FollowChecker_Factory(contextProvider, storeProvider, repositoryProvider, controllerProvider, downloadDaoProvider, settingsProvider);
  }

  public static FollowChecker newInstance(Context context, FollowStore store,
      DownloadsRepository repository, DownloadController controller, DownloadDao downloadDao,
      SettingsStore settings) {
    return new FollowChecker(context, store, repository, controller, downloadDao, settings);
  }
}
