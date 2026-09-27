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
public final class DownloadJobService_MembersInjector implements MembersInjector<DownloadJobService> {
  private final Provider<DownloadQueueRunner> runnerProvider;

  private final Provider<DownloadProgressTracker> progressTrackerProvider;

  private final Provider<DownloadScheduler> schedulerProvider;

  private DownloadJobService_MembersInjector(Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    this.runnerProvider = runnerProvider;
    this.progressTrackerProvider = progressTrackerProvider;
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public void injectMembers(DownloadJobService instance) {
    injectRunner(instance, runnerProvider.get());
    injectProgressTracker(instance, progressTrackerProvider.get());
    injectScheduler(instance, schedulerProvider.get());
  }

  public static MembersInjector<DownloadJobService> create(
      Provider<DownloadQueueRunner> runnerProvider,
      Provider<DownloadProgressTracker> progressTrackerProvider,
      Provider<DownloadScheduler> schedulerProvider) {
    return new DownloadJobService_MembersInjector(runnerProvider, progressTrackerProvider, schedulerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadJobService.runner")
  public static void injectRunner(DownloadJobService instance, DownloadQueueRunner runner) {
    instance.runner = runner;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadJobService.progressTracker")
  public static void injectProgressTracker(DownloadJobService instance,
      DownloadProgressTracker progressTracker) {
    instance.progressTracker = progressTracker;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.DownloadJobService.scheduler")
  public static void injectScheduler(DownloadJobService instance, DownloadScheduler scheduler) {
    instance.scheduler = scheduler;
  }
}
