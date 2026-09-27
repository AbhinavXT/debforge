package com.abhinavxt.debforge.download;

import com.abhinavxt.debforge.data.local.ChunkDao;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.download.conditions.DownloadConditions;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class DownloadQueueRunner_Factory implements Factory<DownloadQueueRunner> {
  private final Provider<ChunkedDownloader> downloaderProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<ChunkDao> chunkDaoProvider;

  private final Provider<DownloadProgressTracker> progressTrackerProvider;

  private final Provider<DownloadConditions> conditionsProvider;

  private final Provider<CompletionNotifier> completionNotifierProvider;

  private final Provider<ServiceCleaner> serviceCleanerProvider;

  private DownloadQueueRunner_Factory(Provider<ChunkedDownloader> downloaderProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<ChunkDao> chunkDaoProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadConditions> conditionsProvider,
      Provider<CompletionNotifier> completionNotifierProvider,
      Provider<ServiceCleaner> serviceCleanerProvider) {
    this.downloaderProvider = downloaderProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.chunkDaoProvider = chunkDaoProvider;
    this.progressTrackerProvider = progressTrackerProvider;
    this.conditionsProvider = conditionsProvider;
    this.completionNotifierProvider = completionNotifierProvider;
    this.serviceCleanerProvider = serviceCleanerProvider;
  }

  @Override
  public DownloadQueueRunner get() {
    return newInstance(downloaderProvider.get(), downloadDaoProvider.get(), chunkDaoProvider.get(), progressTrackerProvider.get(), conditionsProvider.get(), completionNotifierProvider.get(), serviceCleanerProvider.get());
  }

  public static DownloadQueueRunner_Factory create(Provider<ChunkedDownloader> downloaderProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<ChunkDao> chunkDaoProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadConditions> conditionsProvider,
      Provider<CompletionNotifier> completionNotifierProvider,
      Provider<ServiceCleaner> serviceCleanerProvider) {
    return new DownloadQueueRunner_Factory(downloaderProvider, downloadDaoProvider, chunkDaoProvider, progressTrackerProvider, conditionsProvider, completionNotifierProvider, serviceCleanerProvider);
  }

  public static DownloadQueueRunner newInstance(ChunkedDownloader downloader,
      DownloadDao downloadDao, ChunkDao chunkDao, DownloadProgressTracker progressTracker,
      DownloadConditions conditions, CompletionNotifier completionNotifier,
      ServiceCleaner serviceCleaner) {
    return new DownloadQueueRunner(downloader, downloadDao, chunkDao, progressTracker, conditions, completionNotifier, serviceCleaner);
  }
}
