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
public final class ScheduleAlarmReceiver_MembersInjector implements MembersInjector<ScheduleAlarmReceiver> {
  private final Provider<DownloadScheduler> schedulerProvider;

  private ScheduleAlarmReceiver_MembersInjector(Provider<DownloadScheduler> schedulerProvider) {
    this.schedulerProvider = schedulerProvider;
  }

  @Override
  public void injectMembers(ScheduleAlarmReceiver instance) {
    injectScheduler(instance, schedulerProvider.get());
  }

  public static MembersInjector<ScheduleAlarmReceiver> create(
      Provider<DownloadScheduler> schedulerProvider) {
    return new ScheduleAlarmReceiver_MembersInjector(schedulerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.ScheduleAlarmReceiver.scheduler")
  public static void injectScheduler(ScheduleAlarmReceiver instance, DownloadScheduler scheduler) {
    instance.scheduler = scheduler;
  }
}
