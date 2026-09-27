package com.abhinavxt.debforge.widget;

import android.content.Context;
import com.abhinavxt.debforge.data.local.DownloadDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class WidgetSync_Factory implements Factory<WidgetSync> {
  private final Provider<Context> contextProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private WidgetSync_Factory(Provider<Context> contextProvider,
      Provider<DownloadDao> downloadDaoProvider) {
    this.contextProvider = contextProvider;
    this.downloadDaoProvider = downloadDaoProvider;
  }

  @Override
  public WidgetSync get() {
    return newInstance(contextProvider.get(), downloadDaoProvider.get());
  }

  public static WidgetSync_Factory create(Provider<Context> contextProvider,
      Provider<DownloadDao> downloadDaoProvider) {
    return new WidgetSync_Factory(contextProvider, downloadDaoProvider);
  }

  public static WidgetSync newInstance(Context context, DownloadDao downloadDao) {
    return new WidgetSync(context, downloadDao);
  }
}
