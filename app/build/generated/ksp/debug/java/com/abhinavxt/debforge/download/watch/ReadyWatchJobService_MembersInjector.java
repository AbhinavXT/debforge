package com.abhinavxt.debforge.download.watch;

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
public final class ReadyWatchJobService_MembersInjector implements MembersInjector<ReadyWatchJobService> {
  private final Provider<ReadyWatcher> watcherProvider;

  private ReadyWatchJobService_MembersInjector(Provider<ReadyWatcher> watcherProvider) {
    this.watcherProvider = watcherProvider;
  }

  @Override
  public void injectMembers(ReadyWatchJobService instance) {
    injectWatcher(instance, watcherProvider.get());
  }

  public static MembersInjector<ReadyWatchJobService> create(
      Provider<ReadyWatcher> watcherProvider) {
    return new ReadyWatchJobService_MembersInjector(watcherProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.watch.ReadyWatchJobService.watcher")
  public static void injectWatcher(ReadyWatchJobService instance, ReadyWatcher watcher) {
    instance.watcher = watcher;
  }
}
