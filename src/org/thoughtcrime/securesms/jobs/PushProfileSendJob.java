package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.crypto.storage.TextSecureAxolotlStore;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.mms.MediaConstraints;
import org.thoughtcrime.securesms.mms.PartParser;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientFactory;
import org.thoughtcrime.securesms.sms.IncomingIdentityUpdateMessage;
import org.thoughtcrime.securesms.transport.InsecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.SecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.whispersystems.libaxolotl.AxolotlAddress;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.crypto.UntrustedIdentityException;
import org.whispersystems.textsecure.api.messages.TextSecureAttachment;
import org.whispersystems.textsecure.api.messages.TextSecureAttachmentStream;
import org.whispersystems.textsecure.api.messages.TextSecureDataMessage;
import org.whispersystems.textsecure.api.push.TextSecureAddress;
import org.whispersystems.textsecure.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import de.gdata.messaging.util.GDataPreferences;
import de.gdata.messaging.util.GUtil;
import de.gdata.messaging.util.ProfileAccessor;
import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.SendReq;

import static org.thoughtcrime.securesms.dependencies.TextSecureCommunicationModule.TextSecureMessageSenderFactory;

public class PushProfileSendJob extends PushSendJob implements InjectableType {

  private static final String TAG = PushProfileSendJob.class.getSimpleName();

  @Inject transient TextSecureMessageSenderFactory messageSenderFactory;

  private final long messageId;

  public PushProfileSendJob(Context context, long messageId, String destination) {
    super(context, constructParameters(context, destination, true));
    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onSend(MasterSecret masterSecret)
      throws RetryLaterException, MmsException, NoSuchMessageException, UndeliverableMessageException
  {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    SendReq     message  = database.getOutgoingMessage(masterSecret, messageId);

    try {
      deliver(masterSecret, message, true);
      database.markAsPush(messageId);
      database.markAsSecure(messageId);
      database.markAsSent(messageId, "push".getBytes(), 0);

    } catch (InsecureFallbackApprovalException ifae) {
      Log.w(TAG, ifae);
      database.markAsPendingInsecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (UntrustedIdentityException uie) {
      IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(message.getTo()[0].getString(), uie.getIdentityKey());
      DatabaseFactory.getEncryptingSmsDatabase(context).insertMessageInbox(masterSecret, identityUpdateMessage);
      database.markAsSentFailed(messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof RequirementNotMetException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  private void deliver(MasterSecret masterSecret, SendReq message, boolean asProfileUpdate)
          throws RetryLaterException, InsecureFallbackApprovalException, UntrustedIdentityException,
          UndeliverableMessageException
  {
    TextSecureMessageSender messageSender = messageSenderFactory.create(masterSecret);
    String                  destination   = message.getTo()[0].getString();

    try {
      message = getResolvedMessage(masterSecret, message, MediaConstraints.PUSH_CONSTRAINTS, false);

      TextSecureAddress          address      = getPushAddress(destination);
      List<TextSecureAttachment> attachments  = getAttachments(masterSecret, message);
      String                     body         = PartParser.getMessageText(message.getBody());

      ByteArrayInputStream inputStream = new ByteArrayInputStream(("COLOR_TAGS").getBytes("UTF-8"));

      int size = inputStream.available();
      byte[] buf = new byte[size];
      int len = inputStream.read(buf, 0, size);

        GDataPreferences pr = new GDataPreferences(context);
        boolean imageHasChanged = pr.hasProfileImageChanged();
        boolean oldVersion = pr.getVersionForProfileId(GUtil.numberToLong(destination)+"").equals(ProfileAccessor.OLD_VERSION);

        if(!oldVersion) {
            if (!imageHasChanged) {
                attachments.clear();
            }
            attachments.add(0, new TextSecureAttachmentStream(inputStream, ProfileAccessor.TAG_OPEN_PROFILE_COLOR
                    + new GDataPreferences(context).getCurrentColorHex()
                    + ProfileAccessor.TAG_CLOSE_PROFILE_COLOR, len, new TextSecureAttachment.ProgressListener() {
                @Override
                public void onAttachmentProgress(long total, long progress) {
                    //Hopefully useful for later use
                }
            }));

            attachments.add(0, new TextSecureAttachmentStream(inputStream, ProfileAccessor.TAG_OPEN_PROFILE_VERSION
                    + GUtil.getAppVersionCode(context)
                    + ProfileAccessor.TAG_CLOSE_PROFILE_VERSION, len, new TextSecureAttachment.ProgressListener() {
                @Override
                public void onAttachmentProgress(long total, long progress) {
                    //Hopefully useful for later use
                }
            }));
        } else {
            attachments.add(0, new TextSecureAttachmentStream(inputStream, ProfileAccessor.TAG_OPEN_PROFILE_COLOR
                    + new GDataPreferences(context).getCurrentColorHex()
                    + ProfileAccessor.TAG_CLOSE_PROFILE_COLOR, len, new TextSecureAttachment.ProgressListener() {
                @Override
                public void onAttachmentProgress(long total, long progress) {
                    //Hopefully useful for later use
                }
            }));
        }
        TextSecureDataMessage mediaMessage = TextSecureDataMessage.newBuilder()
                .withBody(body)
                .withAttachments(attachments)
                .withTimestamp(message.getSentTimestamp())
                .asProfileUpdate(asProfileUpdate)
                .build();

        messageSender.sendMessage(address, mediaMessage);

    } catch (InvalidNumberException | UnregisteredUserException e) {
      Log.w(TAG, e);
      throw new InsecureFallbackApprovalException(e);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new RetryLaterException(e);
    }
  }

  private void fallbackOrAskApproval(MasterSecret masterSecret, SendReq mediaMessage, String destination)
      throws SecureFallbackApprovalException, InsecureFallbackApprovalException
  {
    Recipient    recipient                     = RecipientFactory.getRecipientsFromString(context, destination, false).getPrimaryRecipient();
    boolean      isSmsFallbackApprovalRequired = isSmsFallbackApprovalRequired(destination, true);
    AxolotlStore axolotlStore                  = new TextSecureAxolotlStore(context, masterSecret);
    AxolotlAddress axolotlAddress              = new AxolotlAddress(recipient.getNumber(), TextSecureAddress.DEFAULT_DEVICE_ID);

    if (!isSmsFallbackApprovalRequired) {
      Log.w(TAG, "Falling back to MMS");
      DatabaseFactory.getMmsDatabase(context).markAsForcedSms(mediaMessage.getDatabaseMessageId());
      ApplicationContext.getInstance(context).getJobManager().add(new MmsSendJob(context, messageId));
    } else if (!axolotlStore.containsSession(axolotlAddress)) {
      Log.w(TAG, "Marking message as pending insecure SMS fallback");
      throw new InsecureFallbackApprovalException("Pending user approval for fallback to insecure SMS");
    } else {
      Log.w(TAG, "Marking message as pending secure SMS fallback");
      throw new SecureFallbackApprovalException("Pending user approval for fallback secure to SMS");
    }
  }


}
