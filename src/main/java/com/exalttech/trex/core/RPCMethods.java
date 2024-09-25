/**
 * *****************************************************************************
 * Copyright (c) 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************
 */
package com.exalttech.trex.core;

import com.cisco.trex.stateless.model.RPCResponse;
import com.exalttech.trex.remote.exceptions.IncorrectRPCMethodException;
import com.exalttech.trex.remote.exceptions.InvalidRPCResponseException;
import com.exalttech.trex.remote.exceptions.PortAcquireException;
import com.exalttech.trex.remote.exceptions.TrafficException;
import com.exalttech.trex.remote.models.TrafficResponse;
import com.exalttech.trex.remote.models.apisync.ApiSyncResult;
import com.exalttech.trex.remote.models.multiplier.Multiplier;
import com.exalttech.trex.remote.models.params.*;
import com.exalttech.trex.remote.models.profiles.Profile;
import com.exalttech.trex.remote.models.validate.StreamValidation;
import com.exalttech.trex.ui.models.Port;
import com.exalttech.trex.ui.views.logs.LogType;
import com.exalttech.trex.ui.views.logs.LogsController;
import com.exalttech.trex.util.Constants;
import com.exalttech.trex.util.TrafficProfile;
import com.exalttech.trex.util.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.log4j.Logger;

import javax.naming.SizeLimitExceededException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

/**
 *
 * @author GeorgeKh
 */
public class RPCMethods {

    private static final Logger LOG = Logger.getLogger(RPCMethods.class.getName());
    private static final int API_VERSION_MAJOR = 5;
    private static final int API_VERSION_MINOR = 0;
    private static final String API_VERSION_TYPE = "core";
    private HashMap connectionHandler = new HashMap();
    private final ConnectionManager serverConnectionManager = ConnectionManager.getInstance();
    private String apiH = "";

    /**
     *
     * @param portID the port ID
     * @param force if force is set to true then the port will be acquired even
     * though it is owned by other
     * @return connectionHandler in case of success
     * @throws PortAcquireException
     */
    public String acquireServerPort(int portID, boolean force) throws PortAcquireException {
        LOG.trace("Acquiring port [" + portID + "]");
        LogsController.getInstance().appendText(LogType.INFO, "Acquiring port [" + portID + "]");
        AcquireParams acquireParams = new AcquireParams();
        acquireParams.setPortId(portID);
        acquireParams.setForce(force);
        acquireParams.setUser(serverConnectionManager.getClientName());
        acquireParams.setSessionId(Util.getRandomID());
        ObjectMapper mapper = new ObjectMapper();

        try {
            String response = serverConnectionManager.sendRPCRequest(Constants.ACQUIRE_METHOD, acquireParams);
            response = Util.removeFirstBrackets(response);

            RPCResponse rpcResult = mapper.readValue(response, RPCResponse.class);
            String handler = mapper.readValue(rpcResult.getResult(), String.class);
            connectionHandler.put(portID, handler);
            serverConnectionManager.propagatePortHandler(portID, handler);
            return handler;

        } catch (InvalidRPCResponseException | IncorrectRPCMethodException | NullPointerException | IOException | SizeLimitExceededException ex) {
            throw new PortAcquireException(ex.getMessage());
        }

    }

    /**
     *
     */
    public void serverApiSync() {
        try {
            String params = "\"api_vers\": [ {\"major\": " + API_VERSION_MAJOR + ",\"minor\": " + API_VERSION_MINOR + ",\"type\": \"" + API_VERSION_TYPE + "\"}]";
            String apiSync = ConnectionManager.getInstance().sendRequest("api_sync", params);
            apiSync = Util.removeFirstBrackets(apiSync);
            ObjectMapper mapper = new ObjectMapper();
            ApiSyncResult apiSyncResult = mapper.readValue(apiSync, ApiSyncResult.class);
            apiH = apiSyncResult.getResult().getApiVers().get(0).getApiH();
            serverConnectionManager.setApiH(apiH);

        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(RPCMethods.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    private boolean buildCommonRPCRequest(int portID, String handler, String method) {
        CommonParams params = new CommonParams(portID, handler);
        try {
            serverConnectionManager.sendRPCRequest(method, params);
            return true;
        } catch (JsonProcessingException | UnsupportedEncodingException | InvalidRPCResponseException | IncorrectRPCMethodException | SizeLimitExceededException ex) {
            return false;
        }

    }

    /**
     *
     * @param portID
     * @param force
     * @param type
     * @param multiplierValue
     * @return multiplier value from the server
     * @throws com.exalttech.trex.remote.exceptions.TrafficException
     */
    public double updateTraffic(int portID, boolean force, String type, double multiplierValue) throws TrafficException {
        LOG.trace("Updating Traffic on port(s) [" + portID + "], setting to " + multiplierValue + " pps");
        LogsController.getInstance().appendText(LogType.INFO, "Updating Traffic on port(s) [" + portID + "], setting to " + multiplierValue + " pps");
        Multiplier trafficMultiplier = new Multiplier(type, multiplierValue);
        String handler = (String) connectionHandler.get(portID);
        TrafficParams trafficParams = new TrafficParams(force, handler, trafficMultiplier, portID);

        try {
            String updateTrafficResponse = serverConnectionManager.sendRPCRequest(Constants.UPDATE_TRAFFIC_METHOD, trafficParams);
            return getMultiplierValue(updateTrafficResponse);

        } catch (JsonProcessingException | UnsupportedEncodingException | InvalidRPCResponseException | IncorrectRPCMethodException | NullPointerException | SizeLimitExceededException ex) {
            throw new TrafficException(ex.toString());
        }

    }

    /**
     * Return multiplier value
     *
     * @param updateTrafficResponse
     * @return
     */
    private double getMultiplierValue(String updateTrafficResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            TrafficResponse trafficResponse = mapper.readValue(Util.removeFirstBrackets(updateTrafficResponse), TrafficResponse.class);
            return trafficResponse.getResult().getMultiplier();
        } catch (IOException ex) {
            return -1;
        }

    }

    /**
     *
     * @param portID
     * @param profileList
     * @return true in case of success
     * @throws java.lang.Exception
     */
    public StreamValidation assignTrafficProfile(int portID, Profile[] profileList) throws Exception {
        String handler = (String) connectionHandler.get(portID);
        stopTraffic(portID);
        removeAllStreams(portID);
        LogsController.getInstance().appendText(LogType.INFO, "Assigning Traffic Profile on Port " + portID);
        TrafficProfile trexTrafficProfile = new TrafficProfile();
        Profile[] updatedProfileList;

        updatedProfileList = trexTrafficProfile.prepareTrafficProfile(profileList, portID, handler);
        serverConnectionManager.sendAddStreamRequest(updatedProfileList);
        return validateStream(portID);

    }

    /**
     *
     * @param portID
     * @param force
     * @param type
     * @param multiplierValue
     * @param duration
     * @return multiplier value from the server
     * @throws com.exalttech.trex.remote.exceptions.TrafficException
     */
    public double startTraffic(int portID, boolean force, String type, double multiplierValue, double duration) throws TrafficException {
        LogsController.getInstance().appendText(LogType.INFO, "Starting Traffic on Port " + portID);
        String handler = (String) connectionHandler.get(portID);
        Multiplier trafficMultiplier = new Multiplier(type, multiplierValue);
        TrafficParams trafficParams = new TrafficParams(force, handler, trafficMultiplier, portID);
        trafficParams.setDuration(duration);
        try {
            String updateTrafficResponse = serverConnectionManager.sendRPCRequest(Constants.START_TRAFFIC_METHOD, trafficParams);
            LOG.trace("Start Traffic response:" + updateTrafficResponse);
            return getMultiplierValue(updateTrafficResponse);

        } catch (JsonProcessingException | UnsupportedEncodingException | InvalidRPCResponseException | IncorrectRPCMethodException | NullPointerException | SizeLimitExceededException ex) {
            throw new TrafficException(ex.toString());
        }
    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     */
    public boolean removeAllStreams(int portID) {
        LOG.trace("Removing all streams from port(s) [" + portID + "]:");
        LogsController.getInstance().appendText(LogType.INFO, "Removing all streams from port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        return buildCommonRPCRequest(portID, handler, Constants.REMOVE_ALL_STREAMS_METHOD);

    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     */
    public boolean stopTraffic(int portID) {
        LogsController.getInstance().appendText(LogType.INFO, "Stopping traffic on port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        return buildCommonRPCRequest(portID, handler, Constants.STOP_TRAFFIC_METHOD);
    }

    /**
     * Remove RX filter command
     *
     * @param portID
     * @return
     */
    public boolean removeRXFilter(int portID) {
        LogsController.getInstance().appendText(LogType.INFO, "Remove RX filter [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        return buildCommonRPCRequest(portID, handler, Constants.REMOVE_RX_FILTER_METHOD);
    }

    /**
     * Stop port traffic
     *
     * @param portID
     */
    public void stopPortTraffic(int portID) {
        stopTraffic(portID);
        removeRXFilter(portID);
    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     */
    public boolean pauseTraffic(int portID) {
        LOG.trace("Pausing Stream from port(s) [" + portID + "]:");
        LogsController.getInstance().appendText(LogType.INFO, "Pausing Stream from port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        return buildCommonRPCRequest(portID, handler, Constants.PAUSE_TRAFFIC_METHOD);
    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     */
    public boolean releaseHandler(int portID) {
        LOG.trace("Releasing Handler from port(s) [" + portID + "]:");
        LogsController.getInstance().appendText(LogType.INFO, "Releasing Handler from port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        boolean released = buildCommonRPCRequest(portID, handler, Constants.RELEASE_HANDLER_METHOD);
        if(released) {
            serverConnectionManager.invalidatePortHandler(portID);
        }
        return released;
    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     */
    public boolean resumeTraffic(int portID) {
        LOG.trace("Resuming Traffic on port(s) [" + portID + "]:");
        LogsController.getInstance().appendText(LogType.INFO, "Resuming Traffic Handler on port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        return buildCommonRPCRequest(portID, handler, Constants.RESUME_TRAFFIC_METHOD);
    }

    /**
     *
     * @param portID
     * @return true if the methods was executed successfully , false otherwise
     * @throws java.lang.Exception
     */
    public StreamValidation validateStream(int portID) throws Exception {
        LOG.trace("Validating Stream on port(s) [" + portID + "]:");
        LogsController.getInstance().appendText(LogType.INFO, "Validating Stream on port(s) [" + portID + "]:");
        String handler = (String) connectionHandler.get(portID);
        CommonParams params = new CommonParams(portID, handler);

        ObjectMapper mapper = new ObjectMapper();
        String response = serverConnectionManager.sendRPCRequest(Constants.VALIDATE_METHOD, params);

        response = Util.removeFirstBrackets(response);

        StreamValidation streamValidationResponse = mapper.readValue(response, StreamValidation.class);
        LOG.trace("Stream Validation response :" + streamValidationResponse.getResult().getRate().toString());
        LogsController.getInstance().appendText(LogType.INFO, "Stream Validation response :" + streamValidationResponse.getResult().getRate().toString());
        return streamValidationResponse;

    }

    /**
     *
     * @param portList
     * @param force
     */
    public void acquireAllServerPorts(List<Port> portList, Boolean force) {
        try {
            connectionHandler.clear();
            for (Port port : portList) {
                String handler = this.acquireServerPort(port.getIndex(), force);
                connectionHandler.put(port.getIndex(), handler);

            }

        } catch (PortAcquireException ex) {
            LOG.error("------" + ex.getMessage());
        }
    }

    /**
     * Release port
     *
     * @param portIndex
     */
    public void releasePort(int portIndex, boolean stopTraffic) {
        if (stopTraffic) {
            stopTraffic(portIndex);
            removeAllStreams(portIndex);
        }
        releaseHandler(portIndex);
    }

    /**
     *
     * @return
     */
    public String getSupportedCmds() {
        LOG.trace("Get supported RPC commands");
        try {
            String response = serverConnectionManager.sendRequest(Constants.GET_SUPPORTED_CMDS_METHOD, "");
            response = Util.removeFirstBrackets(response);
            return response;
        } catch (Exception ex) {
            LOG.error("Failed to ping RPC Server", ex);
            return null;
        }

    }

    /**
     *
     * @return true if the ping was successful false otherwise
     */
    public Boolean pingRPCServer() {
        LOG.trace("Pinging RPC Server");
        try {
            serverConnectionManager.sendRPCRequest(Constants.PING_METHOD, new PingParams());
            LOG.trace("Ping OK");
            return true;
        } catch (JsonProcessingException | UnsupportedEncodingException | InvalidRPCResponseException | IncorrectRPCMethodException | NullPointerException | SizeLimitExceededException ex) {
            LOG.error("Failed to ping RPC Server", ex);
            return false;
        }

    }

    /**
     * @TODO reimplement this. 
     */
    public boolean setPortAttribute(int portID, Boolean link_status, Boolean promiscuous, Boolean led_status, Integer flow_ctrl_mode, Boolean multicast) throws Exception {
        String logstr = "Set attributes on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        PortAttrParams attrs = new PortAttrParams(portID, handler,
                link_status, promiscuous, led_status, flow_ctrl_mode, multicast);

        String response = serverConnectionManager.sendRPCRequest(Constants.SET_PORT_ATTR_METHOD, attrs);

        response = Util.removeFirstBrackets(response);
        return true;
    }

    public boolean setSetL2(int portID, String dst_mac) throws Exception {
        String logstr = "Set l2  on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        L2Params l2params = new L2Params(portID, handler, dst_mac, false);

        String response = serverConnectionManager.sendRPCRequest(Constants.SET_L2_METHOD, l2params);

        response = Util.removeFirstBrackets(response);
        return true;
    }

    public boolean setSetL3(int portID, String dst_ipv4, String src_ipv4) throws Exception {
        String logstr = "Set l3  on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        L3Params l3params = new L3Params(portID, handler, dst_ipv4, src_ipv4, false);

        String response = serverConnectionManager.sendRPCRequest(Constants.SET_L3_METHOD, l3params);

        response = Util.removeFirstBrackets(response);
        return true;
    }

    public String getStreamList(int portID) throws Exception {
        String logstr = "Get stream list  on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        CommonParams params = new CommonParams(portID, handler);

        String response = serverConnectionManager.sendRPCRequest(Constants.PORT_GET_STREAM_LIST_METHOD, params);

        response = Util.removeFirstBrackets(response);
        return response;
    }

    public String getStream(int portID, int streamId) throws Exception {
        String logstr = "Get stream  on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        StreamParams params = new StreamParams(portID, streamId, handler);

        String response = serverConnectionManager.sendRPCRequest(Constants.PORT_GET_STREAM_METHOD, params);

        response = Util.removeFirstBrackets(response);
        return response;
    }

    public String getStreamStats(int portID, int streamId) throws Exception {
        String logstr = "Get stream stats  on port(s) [" + portID + "]:";
        LOG.trace(logstr);
        LogsController.getInstance().appendText(LogType.INFO, logstr);

        String handler = (String) connectionHandler.get(portID);
        StreamParams params = new StreamParams(portID, streamId, handler);

        String response = serverConnectionManager.sendRPCRequest(Constants.PORT_GET_STREAM_STATS_METHOD, params);

        response = Util.removeFirstBrackets(response);
        return response;
    }

}
