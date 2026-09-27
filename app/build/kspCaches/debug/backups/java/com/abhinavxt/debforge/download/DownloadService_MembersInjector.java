package com.abhinavxt.debforge.download;

import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

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
public final class DownloadService_MembersInjector implements MembersInjector<DownloadService> {
  private final Provider<DownloadQueueRunner> runnerProvider;

  private final Provider<DownloadProgressTracker> progressTrackerProvider;

  private final Provider<DownloadScheduler> schedulerProvider;

  private DownloadService_MembersInjector(Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    this.runnerProvider = runnerProvider;
    this.progressTrackerProvider = progressTrackerProvider;
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public void injectMembers(DownloadService instance) {
    injectRunner(instance, runnerProvider.get());
    injectProgressTracker(instance, progressTrackerProvider.get());
    injectScheduler(instance, schedulerProvider.get());
  }

  public static MembersInjector<DownloadService> create(
      Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    return new DownloadService_MembersInjector(runnerProvider, progressTrackerProvider, schedulerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadService.runner")
  public static void injectRunner(DownloadService instance, DownloadQueueRunner runner) {
    instance.runner = runner;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadService.progressTracker")
  public static void injectProgressTracker(DownloadService instance,
      DownloadProgressTracker progressTracker) {
    instance.progressTracker = progressTracker;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadService.scheduler")
  public static void injectScheduler(DownloadService instance, DownloadScheduler scheduler) {
    instance.scheduler = scheduler;
  }
}
