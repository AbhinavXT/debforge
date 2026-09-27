package com.abhinavxt.debforge.widget;

import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.download.DownloadController;
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
public final class DownloadsTileService_MembersInjector implements MembersInjector<DownloadsTileService> {
  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<DownloadController> controllerProvider;

  private DownloadsTileService_MembersInjector(Provider<DownloadDao> downloadDaoProvider,
      Provider<DownloadController> controllerProvider) {
    this.downloadDaoProvider = downloadDaoProvider;
    this.controllerProvider = controllerProvider;
  }

  @Override
  public void injectMembers(DownloadsTileService instance) {
    injectDownloadDao(instance, downloadDaoProvider.get());
    injectController(instance, controllerProvider.get());
  }

  public static MembersInjector<DownloadsTileService> create(
      Provider<DownloadDao> downloadDaoProvider, Provider<DownloadController> controllerProvider) {
    return new DownloadsTileService_MembersInjector(downloadDaoProvider, controllerProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.widget.DownloadsTileService.downloadDao")
  public static void injectDownloadDao(DownloadsTileService instance, DownloadDao downloadDao) {
    instance.downloadDao = downloadDao;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.widget.DownloadsTileService.controller")
  public static void injectController(DownloadsTileService instance,
      DownloadController controller) {
    instance.controller = controller;
  }
}
