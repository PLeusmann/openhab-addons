/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.nikohomecontrol.internal.handler;

import static org.openhab.binding.nikohomecontrol.internal.NikoHomeControlBindingConstants.*;
import static org.openhab.binding.nikohomecontrol.internal.protocol.NikoHomeControlConstants.*;
import static org.openhab.core.types.RefreshType.REFRESH;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.nikohomecontrol.internal.protocol.NhcAction;
import org.openhab.binding.nikohomecontrol.internal.protocol.NhcActionEvent;
import org.openhab.binding.nikohomecontrol.internal.protocol.NikoHomeControlCommunication;
import org.openhab.binding.nikohomecontrol.internal.protocol.NikoHomeControlConstants.ActionType;
import org.openhab.binding.nikohomecontrol.internal.protocol.nhc2.NhcAction2;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NikoHomeControlActionHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Mark Herwege - Initial Contribution
 */
@NonNullByDefault
public class NikoHomeControlActionHandler extends BaseThingHandler implements NhcActionEvent {

    private final Logger logger = LoggerFactory.getLogger(NikoHomeControlActionHandler.class);

    private volatile @Nullable NhcAction nhcAction;

    private volatile boolean initialized = false;

    private String actionId = "";
    private int stepValue;
    private boolean invert;

    public NikoHomeControlActionHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        NikoHomeControlCommunication nhcComm = getCommunication(getBridgeHandler());
        if (nhcComm == null) {
            logger.debug("communication not up yet, cannot handle command {} for {}", command, channelUID);
            return;
        }

        // This can be expensive, therefore do it in a job.
        scheduler.submit(() -> {
            if (!nhcComm.communicationActive()) {
                restartCommunication(nhcComm);
            }

            if (nhcComm.communicationActive()) {
                handleCommandSelection(channelUID, command);
            }
        });
    }

    private void handleCommandSelection(ChannelUID channelUID, Command command) {
        NhcAction nhcAction = this.nhcAction;
        if (nhcAction == null) {
            logger.debug("action with ID {} not initialized", actionId);
            return;
        }

        logger.debug("handle command {} for {}", command, channelUID);

        if (REFRESH.equals(command)) {
            actionEvent(nhcAction.getState());
            return;
        }

        switch (channelUID.getId()) {
            case CHANNEL_BUTTON:
            case CHANNEL_SWITCH:
                handleSwitchCommand(command);
                updateStatus(ThingStatus.ONLINE);
                break;
            case CHANNEL_BRIGHTNESS:
                handleBrightnessCommand(command);
                updateStatus(ThingStatus.ONLINE);
                break;
            case CHANNEL_ROLLERSHUTTER:
                handleRollershutterCommand(command);
                updateStatus(ThingStatus.ONLINE);
                break;
            default:
                logger.debug("unexpected command for channel {}", channelUID.getId());
        }
    }

    private void handleSwitchCommand(Command command) {
        NhcAction nhcAction = this.nhcAction;
        if (nhcAction == null) {
            logger.debug("action with ID {} not initialized", actionId);
            return;
        }

        if (command instanceof OnOffType onOffCommand) {
            if (OnOffType.OFF.equals(onOffCommand)) {
                nhcAction.execute(NHCOFF);
            } else {
                nhcAction.execute(NHCON);
            }
        }
    }

    private void handleBrightnessCommand(Command command) {
        NhcAction nhcAction = this.nhcAction;
        if (nhcAction == null) {
            logger.debug("action with ID {} not initialized", actionId);
            return;
        }

        if (command instanceof OnOffType onOffCommand) {
            if (OnOffType.OFF.equals(onOffCommand)) {
                nhcAction.execute(NHCOFF);
            } else {
                nhcAction.execute(NHCON);
            }
        } else if (command instanceof IncreaseDecreaseType increaseDecreaseCommand) {
            int currentValue = nhcAction.getState();
            int newValue;
            if (IncreaseDecreaseType.INCREASE.equals(increaseDecreaseCommand)) {
                newValue = currentValue + stepValue;
                // round down to step multiple
                newValue = newValue - newValue % stepValue;
                nhcAction.execute(Integer.toString(newValue > 100 ? 100 : newValue));
            } else {
                newValue = currentValue - stepValue;
                // round up to step multiple
                newValue = newValue + newValue % stepValue;
                if (newValue <= 0) {
                    nhcAction.execute(NHCOFF);
                } else {
                    nhcAction.execute(Integer.toString(newValue));
                }
            }
        } else if (command instanceof PercentType percentCommand) {
            if (PercentType.ZERO.equals(percentCommand)) {
                nhcAction.execute(NHCOFF);
            } else {
                nhcAction.execute(Integer.toString(percentCommand.intValue()));
            }
        }
    }

    private void handleRollershutterCommand(Command command) {
        NhcAction nhcAction = this.nhcAction;
        if (nhcAction == null) {
            logger.debug("action with ID {} not initialized", actionId);
            return;
        }

        if (command instanceof UpDownType upDownCommand) {
            if (UpDownType.UP.equals(upDownCommand)) {
                nhcAction.execute(!invert ? NHCUP : NHCDOWN);
            } else {
                nhcAction.execute(!invert ? NHCDOWN : NHCUP);
            }
        } else if (command instanceof StopMoveType) {
            nhcAction.execute(NHCSTOP);
        } else if (command instanceof PercentType percentCommand) {
            nhcAction.execute(!invert ? Integer.toString(100 - percentCommand.intValue())
                    : Integer.toString(percentCommand.intValue()));
        }
    }

    @Override
    public void initialize() {
        initialized = false;

        NikoHomeControlActionConfig config;
        if (thing.getThingTypeUID().equals(THING_TYPE_DIMMABLE_LIGHT)) {
            config = getConfig().as(NikoHomeControlActionDimmerConfig.class);
            stepValue = ((NikoHomeControlActionDimmerConfig) config).step;
        } else if (thing.getThingTypeUID().equals(THING_TYPE_BLIND)) {
            config = getConfig().as(NikoHomeControlActionBlindConfig.class);
            invert = ((NikoHomeControlActionBlindConfig) config).invert;
        } else {
            config = getConfig().as(NikoHomeControlActionConfig.class);
        }
        actionId = config.actionId;

        NikoHomeControlBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.configuration-error.invalid-bridge-handler");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        Bridge bridge = getBridge();
        if ((bridge != null) && ThingStatus.ONLINE.equals(bridge.getStatus())) {
            // We need to do this in a separate thread because we may have to wait for the
            // communication to become active
            scheduler.submit(this::startCommunication);
        }
    }

    private synchronized void startCommunication() {
        NikoHomeControlCommunication nhcComm = getCommunication(getBridgeHandler());

        if (nhcComm == null) {
            return;
        }

        if (!nhcComm.communicationActive()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
            return;
        }

        NhcAction nhcAction = nhcComm.getActions().get(actionId);
        if (nhcAction == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.configuration-error.actionId");
            return;
        }

        nhcAction.setEventHandler(this);

        updateProperties(nhcAction);

        String actionLocation = nhcAction.getLocation();
        if (thing.getLocation() == null) {
            thing.setLocation(actionLocation);
        }

        this.nhcAction = nhcAction;

        logger.debug("action initialized {}", actionId);

        Bridge bridge = getBridge();
        if ((bridge != null) && (bridge.getStatus() == ThingStatus.ONLINE)) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }

        actionEvent(nhcAction.getState());

        initialized = true;
    }

    @Override
    public void dispose() {
        NikoHomeControlCommunication nhcComm = getCommunication(getBridgeHandler());
        if (nhcComm != null) {
            NhcAction action = nhcComm.getActions().get(actionId);
            if (action != null) {
                action.unsetEventHandler();
            }
        }
        nhcAction = null;
        super.dispose();
    }

    private void updateProperties(NhcAction nhcAction) {
        Map<String, String> properties = new HashMap<>();

        properties.put("type", String.valueOf(nhcAction.getType()));
        if (getThing().getThingTypeUID() == THING_TYPE_BLIND) {
            properties.put("timeToOpen", String.valueOf(nhcAction.getOpenTime()));
            properties.put("timeToClose", String.valueOf(nhcAction.getCloseTime()));
        }

        if (nhcAction instanceof NhcAction2 action) {
            properties.put(PROPERTY_DEVICE_TYPE, action.getDeviceType());
            properties.put(PROPERTY_DEVICE_TECHNOLOGY, action.getDeviceTechnology());
            properties.put(PROPERTY_DEVICE_MODEL, action.getDeviceModel());
        }

        thing.setProperties(properties);
    }

    @Override
    public void actionEvent(int actionState) {
        NhcAction nhcAction = this.nhcAction;
        if (nhcAction == null) {
            logger.debug("action with ID {} not initialized", actionId);
            return;
        }

        ActionType actionType = nhcAction.getType();

        switch (actionType) {
            case TRIGGER:
                updateState(CHANNEL_BUTTON, OnOffType.from(actionState != 0));
                updateStatus(ThingStatus.ONLINE);
            case RELAY:
                updateState(CHANNEL_SWITCH, OnOffType.from(actionState != 0));
                updateStatus(ThingStatus.ONLINE);
                break;
            case DIMMER:
                updateState(CHANNEL_BRIGHTNESS, new PercentType(actionState));
                updateStatus(ThingStatus.ONLINE);
                break;
            case ROLLERSHUTTER:
                updateState(CHANNEL_ROLLERSHUTTER,
                        !invert ? new PercentType(100 - actionState) : new PercentType(actionState));
                updateStatus(ThingStatus.ONLINE);
                break;
            default:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.configuration-error.actionType");
        }
    }

    @Override
    public void actionInitialized() {
        Bridge bridge = getBridge();
        if ((bridge != null) && (bridge.getStatus() == ThingStatus.ONLINE)) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    @Override
    public void actionRemoved() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.configuration-error.actionRemoved");
    }

    private void restartCommunication(NikoHomeControlCommunication nhcComm) {
        // We lost connection but the connection object is there, so was correctly started.
        // Try to restart communication.
        nhcComm.scheduleRestartCommunication();
        // If still not active, take thing offline and return.
        if (!nhcComm.communicationActive()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
            return;
        }
        // Also put the bridge back online
        NikoHomeControlBridgeHandler nhcBridgeHandler = getBridgeHandler();
        if (nhcBridgeHandler != null) {
            nhcBridgeHandler.bridgeOnline();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.configuration-error.invalid-bridge-handler");
        }
    }

    private @Nullable NikoHomeControlCommunication getCommunication(
            @Nullable NikoHomeControlBridgeHandler nhcBridgeHandler) {
        return nhcBridgeHandler != null ? nhcBridgeHandler.getCommunication() : null;
    }

    private @Nullable NikoHomeControlBridgeHandler getBridgeHandler() {
        Bridge nhcBridge = getBridge();
        return nhcBridge != null ? (NikoHomeControlBridgeHandler) nhcBridge.getHandler() : null;
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        ThingStatus bridgeStatus = bridgeStatusInfo.getStatus();
        if (ThingStatus.ONLINE.equals(bridgeStatus)) {
            if (!initialized) {
                scheduler.submit(this::startCommunication);
            } else {
                updateStatus(ThingStatus.ONLINE);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }
}
