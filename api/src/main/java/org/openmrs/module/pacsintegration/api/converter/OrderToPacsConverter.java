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
package org.openmrs.module.pacsintegration.api.converter;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v23.message.ORM_O01;
import ca.uhn.hl7v2.model.v23.segment.*;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.parser.PipeParser;
import org.apache.commons.lang.StringUtils;
import org.openmrs.*;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.PatientService;
import org.openmrs.module.emr.EmrProperties;
import org.openmrs.module.emr.radiology.RadiologyOrder;
import org.openmrs.module.pacsintegration.PacsIntegrationConstants;
import org.openmrs.module.pacsintegration.PacsIntegrationGlobalProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Set;

public class OrderToPacsConverter {

    private final SimpleDateFormat pacsDateFormat = new SimpleDateFormat("yyyyMMddHHmm");
    private Parser parser = new PipeParser();

    @Autowired
    private PatientService patientService;

    @Autowired
    private AdministrationService adminService;

    @Autowired
    private ConceptService conceptService;

    @Autowired
    private EmrProperties properties;

    public String convertToPacsFormat(RadiologyOrder order, String orderControl) throws HL7Exception {

        // TODO: some kind of null checking and checking for invalid values

        ORM_O01 message = new ORM_O01();

        // handle the MSH component
        MSH msh = message.getMSH();
        msh.getFieldSeparator().setValue("|");
        msh.getEncodingCharacters().setValue("^~\\&");
        msh.getSendingFacility().getNamespaceID().setValue(adminService.getGlobalProperty(PacsIntegrationGlobalProperties.SENDING_FACILITY));
        msh.getMessageType().getMessageType().setValue("ORM");
        msh.getMessageType().getTriggerEvent().setValue("O01");
        //  TODO: do we need to send Message Control ID?
        msh.getProcessingID().getProcessingID().setValue("P");  // stands for production (?)
        msh.getVersionID().setValue("2.3");

        PID pid = message.getPATIENT().getPID();
        pid.getPatientIDInternalID(0).getID().setValue(order.getPatient().getPatientIdentifier(getPatientIdentifierType()).getIdentifier());
        pid.getPatientName().getFamilyName().setValue(order.getPatient().getFamilyName());
        pid.getPatientName().getGivenName().setValue(order.getPatient().getGivenName());
        pid.getDateOfBirth().getTimeOfAnEvent().setValue(order.getPatient().getBirthdate() != null ? pacsDateFormat.format(order.getPatient().getBirthdate()) : "");
        pid.getSex().setValue(order.getPatient().getGender());
        // TODO: do we need patient admission ID / account number

        PV1 pv1 = message.getPATIENT().getPATIENT_VISIT().getPV1();
        // TODO: do we need patient class
        pv1.getAssignedPatientLocation().getPointOfCare().setValue(order.getExamLocation() != null ? order.getExamLocation().getName() : "");
        if (order.getEncounter() != null) {
        Set<Provider> referringProviders = order.getEncounter().getProvidersByRole(properties.getClinicianEncounterRole());
            if (referringProviders != null && referringProviders.size() > 0) {
                int i = 0;
                for (Provider referringProvider : referringProviders) {
                    // TODO: do we want to add other information here, like the doctor's id number?
                    pv1.getReferringDoctor(i).getFamilyName().setValue(referringProvider.getPerson().getFamilyName());
                    pv1.getReferringDoctor(i).getGivenName().setValue(referringProvider.getPerson().getGivenName());
                    i++;
                }
            }
        }

        ORC orc = message.getORDER().getORC();
        orc.getOrderControl().setValue(orderControl);

        OBR obr = message.getORDER().getORDER_DETAIL().getOBR();
        obr.getFillerOrderNumber().getEntityIdentifier().setValue(order.getAccessionNumber());
        obr.getUniversalServiceIdentifier().getIdentifier().setValue(getProcedureCode(order));

        obr.getUniversalServiceIdentifier().getText().setValue(order.getConcept().getFullySpecifiedName(getDefaultLocale()).getName());

        // note that we are just sending modality here, not the device location
        obr.getPlacerField2().setValue(getModalityCode(order));
        obr.getQuantityTiming().getPriority().setValue(order.getUrgency().equals(Order.Urgency.STAT) ? "STAT" : "");
        // TODO: will this field handle a full clinical history -- what this the max characters
        obr.getReasonForStudy(0).getText().setValue(order.getClinicalHistory());
        obr.getScheduledDateTime().getTimeOfAnEvent().setValue(PacsIntegrationConstants.HL7_DATE_FORMAT.format(order.getStartDate()));

        return parser.encode(message);
    }

    private String getProcedureCode(Order order) {

        if (order.getConcept() == null) {
            throw new RuntimeException("Concept must be specified on an order to send to PACS");
        }

        ConceptSource procedureCodesConceptSource = getProcedureCodesConceptSource();
        ConceptMapType sameAsConceptMapType = getSameAsConceptMapType();

        for (ConceptMap conceptMap : order.getConcept().getConceptMappings()) {
            if (conceptMap.getConceptMapType().equals(sameAsConceptMapType) &&
                    conceptMap.getConceptReferenceTerm().getConceptSource().equals(procedureCodesConceptSource)) {
                // note that this just returns the first code it finds; the assumption is that there is only one SAME-AS
                // code from the specified source for the each concept
                return conceptMap.getConceptReferenceTerm().getCode();
            }
        }

        throw new RuntimeException("No valid procedure code found for concept " + order.getConcept());
    }

    private String getModalityCode(Order order) {

        if (order.getConcept() == null) {
            throw new RuntimeException("Concept must be specified on an order to send to PACS");
        }

        if (properties.getXrayOrderablesConcept().getSetMembers().contains(order.getConcept())) {
            return PacsIntegrationConstants.XRAY_MODALITY_CODE;
        }
        else {
            // TODO: double-check if McKesson PACS will have problem if no modality code is set
            return "";
        }

    }

    private PatientIdentifierType getPatientIdentifierType() {
        PatientIdentifierType patientIdentifierType = patientService.getPatientIdentifierTypeByUuid(adminService.getGlobalProperty(PacsIntegrationGlobalProperties.PATIENT_IDENTIFIER_TYPE_UUID));
        if (patientIdentifierType == null) {
            throw new RuntimeException("No patient identifier type specified. Is pacsintegration.patientIdentifierTypeUuid properly set?");
        }
        else {
            return patientIdentifierType;
        }
    }

    private ConceptSource getProcedureCodesConceptSource() {
        ConceptSource source = conceptService.getConceptSourceByUuid(adminService.getGlobalProperty(PacsIntegrationGlobalProperties.PROCEDURE_CODE_CONCEPT_SOURCE_UUID));
        if (source == null) {
            throw new RuntimeException(("No procedure codes concept source specified. Is pacsintegration.procedureCodeConceptSourceUuid properly set."));
        }
        else {
            return source;
        }
    }

    private Locale getDefaultLocale() {
        String defaultLocale = adminService.getGlobalProperty(PacsIntegrationGlobalProperties.DEFAULT_LOCALE);

        if (StringUtils.isEmpty(defaultLocale)) {
            defaultLocale = "en";
        }

        return new Locale(defaultLocale);
    }

    private ConceptMapType getSameAsConceptMapType()  {
        return conceptService.getConceptMapTypeByUuid(PacsIntegrationConstants.SAME_AS_CONCEPT_MAP_TYPE_UUID);
    }

    public void setPatientService(PatientService patientService) {
        this.patientService = patientService;
    }

    public void setAdminService(AdministrationService adminService) {
        this.adminService = adminService;
    }

    public void setConceptService(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    public void setProperties(EmrProperties properties) {
        this.properties = properties;
    }
}
