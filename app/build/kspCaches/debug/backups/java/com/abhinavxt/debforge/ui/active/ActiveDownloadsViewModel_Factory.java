package com.abhinavxt.debforge.ui.active;

import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.download.DownloadController;
import com.abhinavxt.debforge.download.DownloadProgressTracker;
import com.abhinavxt.debforge.download.conditions.DownloadConditions;
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
public final class ActiveDownloadsViewModel_Factory implements Factory<ActiveDownloadsViewModel> {
  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<DownloadProgressTracker> progressTrackerProvider;

  private final Provider<DownloadController> controllerProvider;

  private final Provider<DownloadConditions> conditionsProvider;

  private ActiveDownloadsViewModel_Factory(Provider<DownloadDao> downloadDaoProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadController> controllerProvider,
      Provider<DownloadConditions> conditionsProvider) {
    this.downloadDaoProvider = downloadDaoProvider;
    this.progressTrackerProvider = progressTrackerProvider;
    this.controllerProvider = controllerProvider;
    this.conditionsProvider = conditionsProvider;
  }

  @Override
  public ActiveDownloadsViewModel get() {
    return newInstance(downloadDaoProvider.get(), progressTrackerProvider.get(), controllerProvider.get(), conditionsProvider.get());
  }

  public static ActiveDownloadsViewModel_Factory create(Provider<DownloadDao> downloadDaoProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadController> controllerProvider,
      Provider<DownloadConditions> conditionsProvider) {
    return new ActiveDownloadsViewModel_Factory(downloadDaoProvider, progressTrackerProvider, controllerProvider, conditionsProvider);
  }

  public static ActiveDownloadsViewModel newInstance(DownloadDao downloadDao,
      DownloadProgressTracker progressTracker, DownloadController controller,
      DownloadConditions conditions) {
    return new ActiveDownloadsViewModel(downloadDao, progressTracker, controller, conditions);
  }
}
