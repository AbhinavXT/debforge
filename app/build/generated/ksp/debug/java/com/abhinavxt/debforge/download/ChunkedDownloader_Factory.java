package com.abhinavxt.debforge.download;

import com.abhinavxt.debforge.data.local.ChunkDao;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
import com.abhinavxt.debforge.data.usage.UsageRecorder;
import com.abhinavxt.debforge.download.conditions.DownloadConditions;
import com.abhinavxt.debforge.download.conditions.RateLimiter;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.abhinavxt.debforge.di.DownloadHttpClient")
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
public final class ChunkedDownloader_Factory implements Factory<ChunkedDownloader> {
  private final Provider<OkHttpClient> clientProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<ChunkDao> chunkDaoProvider;

  private final Provider<DownloadsRepository> downloadsRepositoryProvider;

  private final Provider<DownloadProgressTracker> progressTrackerProvider;

  private final Provider<DownloadConditions> conditionsProvider;

  private final Provider<RateLimiter> rateLimiterProvider;

  private final Provider<UsageRecorder> usageProvider;

  private ChunkedDownloader_Factory(Provider<OkHttpClient> clientProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<ChunkDao> chunkDaoProvider,
      Provider<DownloadsRepository> downloadsRepositoryProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadConditions> conditionsProvider, Provider<RateLimiter> rateLimiterProvider,
      Provider<UsageRecorder> usageProvider) {
    this.clientProvider = clientProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.chunkDaoProvider = chunkDaoProvider;
    this.downloadsRepositoryProvider = downloadsRepositoryProvider;
    this.progressTrackerProvider = progressTrackerProvider;
    this.conditionsProvider = conditionsProvider;
    this.rateLimiterProvider = rateLimiterProvider;
    this.usageProvider = usageProvider;
  }

  @Override
  public ChunkedDownloader get() {
    return newInstance(clientProvider.get(), downloadDaoProvider.get(), chunkDaoProvider.get(), downloadsRepositoryProvider.get(), progressTrackerProvider.get(), conditionsProvider.get(), rateLimiterProvider.get(), usageProvider.get());
  }

  public static ChunkedDownloader_Factory create(Provider<OkHttpClient> clientProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<ChunkDao> chunkDaoProvider,
      Provider<DownloadsRepository> downloadsRepositoryProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadConditions> conditionsProvider, Provider<RateLimiter> rateLimiterProvider,
      Provider<UsageRecorder> usageProvider) {
    return new ChunkedDownloader_Factory(clientProvider, downloadDaoProvider, chunkDaoProvider, downloadsRepositoryProvider, progressTrackerProvider, conditionsProvider, rateLimiterProvider, usageProvider);
  }

  public static ChunkedDownloader newInstance(OkHttpClient client, DownloadDao downloadDao,
      ChunkDao chunkDao, DownloadsRepository downloadsRepository,
      DownloadProgressTracker progressTracker, DownloadConditions conditions,
      RateLimiter rateLimiter, UsageRecorder usage) {
    return new ChunkedDownloader(client, downloadDao, chunkDao, downloadsRepository, progressTracker, conditions, rateLimiter, usage);
  }
}
