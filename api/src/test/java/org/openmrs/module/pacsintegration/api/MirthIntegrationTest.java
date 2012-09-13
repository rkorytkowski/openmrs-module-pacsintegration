/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.pacsintegration.api;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openmrs.Order;
import org.openmrs.api.context.Context;
import org.openmrs.module.pacsintegration.ConversionUtils;
import org.openmrs.module.pacsintegration.OrmMessage;
import org.openmrs.module.pacsintegration.TransmissionUtils;
import org.openmrs.test.BaseModuleContextSensitiveTest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

@Ignore
public class MirthIntegrationTest extends BaseModuleContextSensitiveTest {

    protected final Log log = LogFactory.getLog(getClass());

    protected static final String XML_DATASET = "org/openmrs/module/pacsintegration/include/pacsIntegrationTestDataset.xml";

    @Before
    public void setupDatabase() throws Exception {

        // TODO: also need to configure Mirth channel, and break it down afterwards

        executeDataSet(XML_DATASET);
    }


    @Test
    public void sendMessage_shouldSendMessageToMirth() throws Exception {

        Order order = Context.getOrderService().getOrder(1001);
        OrmMessage ormMessage = ConversionUtils.createORMMessage(order, "SC");

        // TODO: these are to mock the fields we aren't current handling--these should eventually be removed so that we properly test these fields once we handle them
        ormMessage.setDeviceLocation("E");
        ormMessage.setSendingFacility("A");
        ormMessage.setUniversalServiceID("B");
        ormMessage.setUniversalServiceIDText("C");
        ormMessage.setModality("D");

        TransmissionUtils.sendMessage(ormMessage);

        String result = listenForResults();

        TestUtils.assertContains("MSH|^~\\&||A|||||ORM^O01||P|2.2|||||", result);
        TestUtils.assertContains("PID|||6TS-4||Chebaskwony^Collet||197608250000|F||||||||||||||||||", result);
        TestUtils.assertContains("PV1||||||||||||||||||", result);
        TestUtils.assertContains("ORC|SC||||||||||||||||||", result);
        TestUtils.assertContains("OBR|||54321|B^C|||||||||||||||E^D|||||||||||||||||200808080000", result);

    }


    private String listenForResults() throws IOException {

        ServerSocket listener = new ServerSocket(6660);
        listener.setSoTimeout(5000);  // don't wait more than 5 seconds for an incoming connection

        Socket mirthConnection = listener.accept();

        BufferedReader reader = new BufferedReader(new InputStreamReader(mirthConnection.getInputStream()));

        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        // TODO: need an acknowledgement?

        mirthConnection.close();

        return sb.toString();
    }
}
