package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.jobqueue.JobParameters;

public abstract class SendJob extends MasterSecretJob {

  public SendJob(Context context, JobParameters parameters) {
    super(context, parameters);
  }

  @Override
  public final void onRun(MasterSecret masterSecret) throws Exception {
    Util.verifyBuildFreshness();
    onRunSend(masterSecret);
  }

  protected abstract void onRunSend(MasterSecret masterSecret) throws Exception;
}
