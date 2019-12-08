package info.nightscout.androidaps.plugins.pump.omnipod;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import info.nightscout.androidaps.BuildConfig;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.EventRefreshOverview;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.general.actions.defs.CustomAction;
import info.nightscout.androidaps.plugins.general.actions.defs.CustomActionType;
import info.nightscout.androidaps.plugins.pump.common.PumpPluginAbstract;
import info.nightscout.androidaps.plugins.pump.common.data.TempBasalPair;
import info.nightscout.androidaps.plugins.pump.common.defs.PumpDriverState;
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkConst;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.tasks.ResetRileyLinkConfigurationTask;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodCommandType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodCommunicationManagerInterface;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodCustomActionType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodPodType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodPumpPluginInterface;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.OmnipodStatusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.PodInitActionType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.state.PodSessionState;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.OmnipodPumpStatus;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.comm.AapsOmnipodManager;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.ui.OmnipodUIComm;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.ui.OmnipodUITask;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodPumpValuesChanged;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodRefreshButtonState;
import info.nightscout.androidaps.plugins.pump.omnipod.service.RileyLinkOmnipodService;
import info.nightscout.androidaps.plugins.pump.omnipod.util.LogReceiver;
import info.nightscout.androidaps.plugins.pump.omnipod.util.OmnipodConst;
import info.nightscout.androidaps.plugins.pump.omnipod.util.OmnipodUtil;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.OKDialog;
import info.nightscout.androidaps.utils.SP;

/**
 * Created by andy on 23.04.18.
 *
 * @author Andy Rozman (andy.rozman@gmail.com)
 */
public class OmnipodPumpPlugin extends PumpPluginAbstract implements OmnipodPumpPluginInterface {

    private static final Logger LOG = LoggerFactory.getLogger(L.PUMP);

    private static OmnipodPumpPlugin plugin = null;
    private RileyLinkOmnipodService omnipodService;
    protected OmnipodPumpStatus pumpStatusLocal = null;
    protected OmnipodUIComm omnipodUIComm;

    // variables for handling statuses and history
    protected boolean firstRun = true;
    protected boolean isRefresh = false;
    private boolean isBasalProfileInvalid = false;
    private boolean basalProfileChanged = false;
    private boolean isInitialized = false;
    protected OmnipodCommunicationManagerInterface omnipodCommunicationManager;

    public static boolean isBusy = false;
    protected List<Long> busyTimestamps = new ArrayList<>();
    protected boolean sentIdToFirebase = false;
    protected boolean hasTimeDateOrTimeZoneChanged = false;

    private Profile currentProfile;


    private OmnipodPumpPlugin() {

        super(new PluginDescription() //
                        .mainType(PluginType.PUMP) //
                        .fragmentClass(OmnipodFragment.class.getName()) //
                        .pluginName(R.string.omnipod_name) //
                        .shortName(R.string.omnipod_name_short) //
                        .preferencesId(R.xml.pref_omnipod) //
                        .description(R.string.description_pump_omnipod), //
                PumpType.Insulet_Omnipod
        );

        displayConnectionMessages = false;

        OmnipodUtil.setOmnipodPodType(OmnipodPodType.Eros);
        OmnipodUtil.setPlugin(this);

        serviceConnection = new ServiceConnection() {

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (isLoggingEnabled())
                    LOG.debug("RileyLinkOmnipodService is disconnected");
                omnipodService = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (isLoggingEnabled())
                    LOG.debug("RileyLinkOmnipodService is connected");
                RileyLinkOmnipodService.LocalBinder mLocalBinder = (RileyLinkOmnipodService.LocalBinder) service;
                omnipodService = mLocalBinder.getServiceInstance();

                new Thread(() -> {

                    for (int i = 0; i < 20; i++) {

                        if (pumpStatusLocal != null) {
                            if (isLoggingEnabled())
                                LOG.debug("Starting OmniPod-RileyLink service");
                            if (OmnipodUtil.getPumpStatus().setNotInPreInit()) {
                                if (omnipodCommunicationManager == null) {
                                    omnipodCommunicationManager = AapsOmnipodManager.getInstance();
                                    omnipodCommunicationManager.setPumpStatus(pumpStatusLocal);
                                } else {
                                    omnipodCommunicationManager.setPumpStatus(pumpStatusLocal);
                                }

                                omnipodUIComm = new OmnipodUIComm(omnipodCommunicationManager, plugin, pumpStatusLocal);
                                break;
                            }
                        }

                        SystemClock.sleep(5000);
                    }
                }).start();
            }
        };
    }

    protected OmnipodPumpPlugin(PluginDescription pluginDescription, PumpType pumpType) {
        super(pluginDescription, pumpType);
    }

    public static OmnipodPumpPlugin getPlugin() {
        if (plugin == null)
            plugin = new OmnipodPumpPlugin();
        return plugin;
    }


    private String getLogPrefix() {
        return "OmnipodPlugin::";
    }


    @Override
    public void initPumpStatusData() {

        this.pumpStatusLocal = new OmnipodPumpStatus(pumpDescription);
        OmnipodUtil.setPumpStatus(pumpStatusLocal);

        pumpStatusLocal.lastConnection = SP.getLong(RileyLinkConst.Prefs.LastGoodDeviceCommunicationTime, 0L);
        pumpStatusLocal.lastDataTime = new LocalDateTime(pumpStatusLocal.lastConnection);
        pumpStatusLocal.previousConnection = pumpStatusLocal.lastConnection;

        pumpStatusLocal.refreshConfiguration();

        if (isLoggingEnabled())
            LOG.debug("initPumpStatusData: {}", this.pumpStatusLocal);

        this.pumpStatus = pumpStatusLocal;

        // set first Omnipod Start
        if (!SP.contains(OmnipodConst.Statistics.FirstPumpStart)) {
            SP.putLong(OmnipodConst.Statistics.FirstPumpStart, System.currentTimeMillis());
        }

    }


    @Override
    public void onStartCustomActions() {

        // check status every minute (if any status needs refresh we send readStatus command)
        new Thread(() -> {

            do {
                SystemClock.sleep(60000);

                if (this.isInitialized) {
                    clearBusyQueue();
                }

                if (!this.omnipodStatusRequestList.isEmpty()) {
                    if (!ConfigBuilderPlugin.getPlugin().getCommandQueue().statusInQueue()) {
                        ConfigBuilderPlugin.getPlugin().getCommandQueue()
                                .readStatus("Status Refresh Requested", null);
                    }
                }

            } while (serviceRunning);

        }).start();
    }


    @Override
    public Class getServiceClass() {
        return RileyLinkOmnipodService.class;
    }


    @Override
    public String deviceID() {
        return "Omnipod";
    }


    // Pump Plugin

    private boolean isServiceSet() {
        return omnipodService != null;
    }


    @Override
    public boolean isInitialized() {
        if (isLoggingEnabled() && displayConnectionMessages)
            LOG.debug(getLogPrefix() + "isInitialized");
        return isServiceSet() && isInitialized;
    }


    @Override
    public boolean isBusy() {
        if (isLoggingEnabled() && displayConnectionMessages)
            LOG.debug(getLogPrefix() + "isBusy");

        if (isServiceSet()) {

            if (isBusy || !pumpStatusLocal.podAvailable)
                return true;

            if (busyTimestamps.size() > 0) {

                clearBusyQueue();

                return (busyTimestamps.size() > 0);
            }
        }

        return false;
    }


    private synchronized void clearBusyQueue() {

        if (busyTimestamps.size() == 0) {
            return;
        }

        Set<Long> deleteFromQueue = new HashSet<>();

        for (Long busyTimestamp : busyTimestamps) {

            if (System.currentTimeMillis() > busyTimestamp) {
                deleteFromQueue.add(busyTimestamp);
            }
        }

        if (deleteFromQueue.size() == busyTimestamps.size()) {
            busyTimestamps.clear();
            //setEnableCustomAction(MedtronicCustomActionType.ClearBolusBlock, false);
        }

        if (deleteFromQueue.size() > 0) {
            busyTimestamps.removeAll(deleteFromQueue);
        }

    }


    @Override
    public boolean isConnected() {
        if (isLoggingEnabled() && displayConnectionMessages)
            LOG.debug(getLogPrefix() + "isConnected");
        return isServiceSet() && omnipodService.isInitialized();
    }


    @Override
    public boolean isConnecting() {
        if (isLoggingEnabled() && displayConnectionMessages)
            LOG.debug(getLogPrefix() + "isConnecting");
        return !isServiceSet() || !omnipodService.isInitialized();
    }


    @Override
    public boolean isSuspended() {
        return (pumpStatusLocal != null && !pumpStatusLocal.podAvailable);
    }

    @Override
    public void getPumpStatus() {

        if (firstRun) {
            initializePump(!isRefresh);
            triggerUIChange();
        } else if (!omnipodStatusRequestList.isEmpty()) {

            List<OmnipodStatusRequest> removeList = new ArrayList<>();

            for (OmnipodStatusRequest omnipodStatusRequest : omnipodStatusRequestList) {
                // TODO when we get more commands this needs to be extended
                omnipodUIComm.executeCommand(omnipodStatusRequest.getCommandType());
                removeList.add(omnipodStatusRequest);
            }

            omnipodStatusRequestList.removeAll(removeList);

            //getPodPumpStatus();
        }

    }


    public void setIsBusy(boolean isBusy_) {
        isBusy = isBusy_;
    }


    private void getPodPumpStatus() {
        // TODO read pod status
        LOG.error("getPodPumpStatus() NOT IMPLEMENTED");

        // we would probably need to read Basal Profile here too
    }


    List<OmnipodStatusRequest> omnipodStatusRequestList = new ArrayList<>();

    public void addPodStatusRequest(OmnipodStatusRequest pumpStatusRequest) {
        if (pumpStatusRequest == OmnipodStatusRequest.ResetState) {
            resetStatusState();
        } else {
            omnipodStatusRequestList.add(pumpStatusRequest);
        }
    }


    public void resetStatusState() {
        firstRun = true;
        isRefresh = true;
    }


    private void setRefreshButtonEnabled(boolean enabled) {
        RxBus.INSTANCE.send(new EventOmnipodRefreshButtonState(enabled));
    }


    private void initializePump(boolean realInit) {

        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "initializePump - start");


        setRefreshButtonEnabled(false);

        PodSessionState podSessionState = null;

        if (OmnipodUtil.getPodSessionState()!=null) {
            podSessionState = OmnipodUtil.getPodSessionState();
        } else {
            String podState = SP.getString(OmnipodConst.Prefs.PodState, null);

            if (podState != null) {
                podSessionState = OmnipodUtil.getGsonInstance().fromJson(podState, PodSessionState.class);
                OmnipodUtil.setPodSessionState(podSessionState);
            }
        }

        if (podSessionState!=null) {
            LOG.debug("PodSessionState (saved): " + podSessionState);

            // TODO handle if session state too old

            // TODO handle basal

            // TODO handle time

            if (!isRefresh) {
                pumpState = PumpDriverState.Initialized;
            }

            triggerUIChange();
            RxBus.INSTANCE.send(new EventOmnipodPumpValuesChanged());

            getPodPumpStatus();

        } else {
            LOG.debug("No PodSessionState found. Pod probably not running.");
        }


        setRefreshButtonEnabled(true);


        if (!sentIdToFirebase) {
            Bundle params = new Bundle();
            params.putString("version", BuildConfig.VERSION);
            MainApp.getFirebaseAnalytics().logEvent("OmnipodPumpInit", params);

            sentIdToFirebase = true;
        }

        isInitialized = true;

        this.firstRun = false;
    }


    @Override
    public boolean isThisProfileSet(Profile profile) {

        // TODO status was not yet read from pod
        // TODO maybe not possible, need to see how we will handle that
        if (currentProfile == null) {
            this.currentProfile = profile;
            return true;
        }

        return (currentProfile.isProfileTheSame(profile));
    }


    @Override
    public long lastDataTime() {
        getPodPumpStatusObject();

        if (pumpStatusLocal.lastConnection != 0) {
            return pumpStatusLocal.lastConnection;
        }

        return System.currentTimeMillis();
    }


    @Override
    public double getBaseBasalRate() {

        if (currentProfile != null) {
            int hour = (new GregorianCalendar()).get(Calendar.HOUR_OF_DAY);
            return currentProfile.getBasalTimeFromMidnight(getTimeInS(hour * 60));
        } else {
            return 0.0d;
        }
    }


    @Override
    public double getReservoirLevel() {
        return getPodPumpStatusObject().reservoirRemainingUnits;
    }


    @Override
    public int getBatteryLevel() {
        return 75;
    }


    protected OmnipodPumpStatus getPodPumpStatusObject() {
        if (pumpStatusLocal == null) {
            // FIXME I don't know why this happens
            if (isLoggingEnabled())
                LOG.warn("!!!! Reset Pump Status Local");
            pumpStatusLocal = OmnipodUtil.getPumpStatus();
        }

        return pumpStatusLocal;
    }


    @Override
    protected void triggerUIChange() {
        RxBus.INSTANCE.send(new EventOmnipodPumpValuesChanged());
    }


    @Override
    public boolean isFakingTempsByExtendedBoluses() {
        return false;
    }


    @Override
    @NonNull
    protected PumpEnactResult deliverBolus(final DetailedBolusInfo detailedBolusInfo) {

        LOG.info(getLogPrefix() + "deliverBolus - {}", detailedBolusInfo);

        setRefreshButtonEnabled(false);

        try {

            OmnipodUITask responseTask = omnipodUIComm.executeCommand(OmnipodCommandType.SetBolus,
                    detailedBolusInfo.insulin, detailedBolusInfo.isSMB);

            PumpEnactResult result = responseTask.getResult();

            setRefreshButtonEnabled(true);

            if (result.success) {

                TreatmentsPlugin.getPlugin().addToHistoryTreatment(detailedBolusInfo, true);

                // we subtract insulin, exact amount will be visible with next remainingInsulin update.
                if (getPodPumpStatusObject().reservoirRemainingUnits != 0) {
                    getPodPumpStatusObject().reservoirRemainingUnits -= detailedBolusInfo.insulin;
                }

                incrementStatistics(detailedBolusInfo.isSMB ? OmnipodConst.Statistics.SMBBoluses
                        : OmnipodConst.Statistics.StandardBoluses);

                // calculate time for bolus and set driver to busy for that time

                // TODO fix this
                int bolusTime = 1; //omnipodCommunicationManager.get;
                long time = System.currentTimeMillis() + (bolusTime * 1000);

                this.busyTimestamps.add(time);
                result.bolusDelivered(detailedBolusInfo.insulin).carbsDelivered(detailedBolusInfo.carbs);
            }

            return result;
        } finally {
            finishAction("Bolus");
        }
    }

    @Override
    public void stopBolusDelivering() {
        LOG.info(getLogPrefix() + "stopBolusDelivering");

        setRefreshButtonEnabled(false);

        OmnipodUITask responseTask = omnipodUIComm.executeCommand(OmnipodCommandType.CancelBolus);

        PumpEnactResult result = responseTask.getResult();

        setRefreshButtonEnabled(true);

        LOG.info(getLogPrefix() + "stopBolusDelivering - wasSuccess={}", result.success);

        if (result.success) {
            // TODO fix bolus record with cancel

            //TreatmentsPlugin.getPlugin().addToHistoryTreatment(detailedBolusInfo, true);
        }

        finishAction("Bolus");
    }


    private void incrementStatistics(String statsKey) {
        long currentCount = SP.getLong(statsKey, 0L);
        currentCount++;
        SP.putLong(statsKey, currentCount);
    }


    // if enforceNew===true current temp basal is canceled and new TBR set (duration is prolonged),
    // if false and the same rate is requested enacted=false and success=true is returned and TBR is not changed
    @Override
    public PumpEnactResult setTempBasalAbsolute(Double absoluteRate, Integer durationInMinutes, Profile profile,
                                                boolean enforceNew) {

        getPodPumpStatusObject();

        setRefreshButtonEnabled(false);

        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "setTempBasalAbsolute: rate: {}, duration={}", absoluteRate, durationInMinutes);

        // read current TBR
        TempBasalPair tbrCurrent = readTBR();

        if (tbrCurrent != null) {
            if (isLoggingEnabled())
                LOG.info(getLogPrefix() + "setTempBasalAbsolute: Current Basal: duration: {} min, rate={}",
                        tbrCurrent.getDurationMinutes(), tbrCurrent.getInsulinRate());
        }

        if (tbrCurrent != null && !enforceNew) {
            if (OmnipodUtil.isSame(tbrCurrent.getInsulinRate(), absoluteRate)) {
                if (isLoggingEnabled())
                    LOG.info(getLogPrefix() + "setTempBasalAbsolute - No enforceNew and same rate. Exiting.");
                finishAction("TBR");
                return new PumpEnactResult().success(true).enacted(false);
            }
        }

        // if TBR is running we will cancel it.
        if (tbrCurrent != null) {
            if (isLoggingEnabled())
                LOG.info(getLogPrefix() + "setTempBasalAbsolute - TBR running - so canceling it.");

            // CANCEL
            OmnipodUITask responseTask2 = omnipodUIComm.executeCommand(OmnipodCommandType.CancelTemporaryBasal);

            PumpEnactResult result = responseTask2.getResult();

            if (result.success) {
                if (isLoggingEnabled())
                    LOG.info(getLogPrefix() + "setTempBasalAbsolute - Current TBR cancelled.");
            } else {
                if (isLoggingEnabled())
                    LOG.error(getLogPrefix() + "setTempBasalAbsolute - Cancel TBR failed.");

                finishAction("TBR");

                return result;
            }
        }

        // now start new TBR
        OmnipodUITask responseTask = omnipodUIComm.executeCommand(OmnipodCommandType.SetTemporaryBasal,
                absoluteRate, durationInMinutes);

        PumpEnactResult result = responseTask.getResult();

        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "setTempBasalAbsolute - setTBR. Response: " + result.success);

        if (result.success) {
            pumpStatusLocal.tempBasalStart = System.currentTimeMillis();
            pumpStatusLocal.tempBasalAmount = absoluteRate;
            pumpStatusLocal.tempBasalLength = durationInMinutes;
            pumpStatusLocal.tempBasalEnd = getTimeInFutureFromMinutes(durationInMinutes);

            TemporaryBasal tempStart = new TemporaryBasal() //
                    .date(System.currentTimeMillis()) //
                    .duration(durationInMinutes) //
                    .absolute(absoluteRate) //
                    .source(Source.USER);

            TreatmentsPlugin.getPlugin().addToHistoryTempBasal(tempStart);

            incrementStatistics(OmnipodConst.Statistics.TBRsSet);
        }

        finishAction("TBR");
        return result;
    }

    protected TempBasalPair readTBR() {
        // TODO we can do it like this or read status from pod ??
        if (pumpStatusLocal.tempBasalEnd < System.currentTimeMillis()) {
            // TBR done
            pumpStatusLocal.clearTemporaryBasal();

            return null;
        }

        return pumpStatusLocal.getTemporaryBasal();
    }


    protected void finishAction(String overviewKey) {
        if (overviewKey != null)
            RxBus.INSTANCE.send(new EventRefreshOverview(overviewKey));

        triggerUIChange();

        setRefreshButtonEnabled(true);
    }


    protected long getTimeInFutureFromMinutes(int minutes) {
        return System.currentTimeMillis() + getTimeInMs(minutes);
    }


    protected long getTimeInMs(int minutes) {
        return getTimeInS(minutes) * 1000L;
    }

    protected int getTimeInS(int minutes) {
        return minutes * 60;
    }


    @Override
    public PumpEnactResult cancelTempBasal(boolean enforceNew) {

        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "cancelTempBasal - started");

        setRefreshButtonEnabled(false);

        TempBasalPair tbrCurrent = readTBR();

        if (tbrCurrent == null) {

            if (isLoggingEnabled())
                LOG.info(getLogPrefix() + "cancelTempBasal - TBR already canceled.");
            finishAction("TBR");
            return new PumpEnactResult().success(true).enacted(false);

        }

        OmnipodUITask responseTask2 = omnipodUIComm.executeCommand(OmnipodCommandType.CancelTemporaryBasal);

        PumpEnactResult result = responseTask2.getResult();

        finishAction("TBR");

        if (result.success) {
            if (isLoggingEnabled())
                LOG.info(getLogPrefix() + "cancelTempBasal - Cancel TBR successful.");

            TemporaryBasal tempBasal = new TemporaryBasal() //
                    .date(System.currentTimeMillis()) //
                    .duration(0) //
                    .source(Source.USER);

            TreatmentsPlugin.getPlugin().addToHistoryTempBasal(tempBasal);
        } else {
            if (isLoggingEnabled())
                LOG.info(getLogPrefix() + "cancelTempBasal - Cancel TBR failed.");
        }

        return result;
    }

    @Override
    public String serialNumber() {
        return getPodPumpStatusObject().podNumber;
    }

    @Override
    public PumpEnactResult setNewBasalProfile(Profile profile) {
        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "setNewBasalProfile");

        // this shouldn't be needed, but let's do check if profile setting we are setting is same as current one
        if (this.currentProfile != null && this.currentProfile.isProfileTheSame(profile)) {
            return new PumpEnactResult() //
                    .success(true) //
                    .enacted(false) //
                    .comment(MainApp.gs(R.string.medtronic_cmd_basal_profile_not_set_is_same));
        }

        setRefreshButtonEnabled(false);

        OmnipodUITask responseTask = omnipodUIComm.executeCommand(OmnipodCommandType.SetBasalProfile,
                profile);

        PumpEnactResult result = responseTask.getResult();

        if (isLoggingEnabled())
            LOG.info(getLogPrefix() + "Basal Profile was set: " + result.success);

        if (result.success) {
            this.currentProfile = profile;
        }

        return result;
    }


    // OPERATIONS not supported by Pump or Plugin

    protected List<CustomAction> customActions = null;

    private CustomAction customActionResetRLConfig = new CustomAction(
            R.string.medtronic_custom_action_reset_rileylink, OmnipodCustomActionType.ResetRileyLinkConfiguration, true);

//    protected CustomAction customActionPairAndPrime = new CustomAction(
//            R.string.omnipod_cmd_init_pod, OmnipodCustomActionType.PairAndPrime, true);
//
//    protected CustomAction customActionFillCanullaSetBasalProfile = new CustomAction(
//            R.string.omnipod_cmd_init_pod, OmnipodCustomActionType.FillCanulaSetBasalProfile, false);
//
//    protected CustomAction customActionDeactivatePod = new CustomAction(
//            R.string.omnipod_cmd_deactivate_pod, OmnipodCustomActionType.DeactivatePod, false);
//
//    protected CustomAction customActionResetPod = new CustomAction(
//            R.string.omnipod_cmd_reset_pod, OmnipodCustomActionType.ResetPodStatus, true);


    @Override
    public List<CustomAction> getCustomActions() {

        if (customActions == null) {
            this.customActions = Arrays.asList(
                    customActionResetRLConfig //,
                    //customActionPairAndPrime, //
                    //customActionFillCanullaSetBasalProfile, //
                    //customActionDeactivatePod, //
                    //customActionResetPod
            );
        }

        return this.customActions;

    }


    @Override
    public void executeCustomAction(CustomActionType customActionType) {

        OmnipodCustomActionType mcat = (OmnipodCustomActionType) customActionType;

        switch (mcat) {

            case ResetRileyLinkConfiguration: {
                ServiceTaskExecutor.startTask(new ResetRileyLinkConfigurationTask());
            }
            break;

            default:
                break;
        }

    }

    @Override
    public void timeDateOrTimeZoneChanged() {

//        if (isLoggingEnabled())
//            LOG.warn(getLogPrefix() + "Time, Date and/or TimeZone changed. ");
//
//        this.hasTimeDateOrTimeZoneChanged = true;

        // TODO
    }


    public void setEnableCustomAction(OmnipodCustomActionType customAction, boolean isEnabled) {
    }


}
