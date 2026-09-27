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
public final class ScheduleRearmReceiver_MembersInjector implements MembersInjector<ScheduleRearmReceiver> {
  private final Provider<DownloadScheduler> schedulerProvider;

  private ScheduleRearmReceiver_MembersInjector(Provider<DownloadScheduler> schedulerProvider) {
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public void injectMembers(ScheduleRearmReceiver instance) {
    injectScheduler(instance, schedulerProvider.get());
  }

  public static MembersInjector<ScheduleRearmReceiver> create(
      Provider<DownloadScheduler> schedulerProvider) {
    return new ScheduleRearmReceiver_MembersInjector(schedulerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.ScheduleRearmReceiver.scheduler")
  public static void injectScheduler(ScheduleRearmReceiver instance, DownloadScheduler scheduler) {
    instance.scheduler = scheduler;
  }
}
