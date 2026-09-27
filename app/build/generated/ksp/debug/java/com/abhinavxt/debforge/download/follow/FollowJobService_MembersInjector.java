package com.abhinavxt.debforge.download.follow;

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
public final class FollowJobService_MembersInjector implements MembersInjector<FollowJobService> {
  private final Provider<FollowChecker> checkerProvider;

  private FollowJobService_MembersInjector(Provider<FollowChecker> checkerProvider) {
    this.checkerProvider = checkerProvider;
  }

  @Override
  public void injectMembers(FollowJobService instance) {
    injectChecker(instance, checkerProvider.get());
  }

  public static MembersInjector<FollowJobService> create(Provider<FollowChecker> checkerProvider) {
    return new FollowJobService_MembersInjector(checkerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.download.follow.FollowJobService.checker")
  public static void injectChecker(FollowJobService instance, FollowChecker checker) {
    instance.checker = checker;
  }
}
