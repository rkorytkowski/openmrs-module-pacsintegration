package org.openmrs.module.pacsintegration.handler;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.LocationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.ProviderService;
import org.openmrs.module.radiologyapp.RadiologyOrder;
import org.openmrs.module.radiologyapp.RadiologyService;
import org.openmrs.module.emrapi.EmrApiProperties;
import org.openmrs.module.pacsintegration.PacsIntegrationConstants;
import org.openmrs.module.pacsintegration.PacsIntegrationException;
import org.openmrs.module.pacsintegration.PacsIntegrationProperties;

import java.util.Collections;
import java.util.Date;
import java.util.List;

abstract public class HL7Handler {

    protected final Log log = LogFactory.getLog(this.getClass());

    protected PatientService patientService;

    protected ConceptService conceptService;

    protected AdministrationService adminService;

    protected RadiologyService radiologyService;

    protected LocationService locationService;

    protected ProviderService providerService;

    protected EmrApiProperties emrApiProperties;

    protected PacsIntegrationProperties pacsIntegrationProperties;


    protected Patient getPatient(String patientIdentifier) {
        if (StringUtils.isBlank(patientIdentifier)) {
            throw new PacsIntegrationException("Cannot import message. No patient identifier specified.");
        }

        List<Patient> patientList = patientService.getPatients(null, patientIdentifier,
                Collections.singletonList(emrApiProperties.getPrimaryIdentifierType()), true);

        if (patientList == null || patientList.size() == 0) {
            throw new PacsIntegrationException("Cannot import message. No patient with identifier " + patientIdentifier);
        }

        if (patientList.size() > 1) {
            throw new PacsIntegrationException("Cannot import message. Multiple patients with identifier " + patientIdentifier);
        }

        return patientList.get(0);
    }

    protected RadiologyOrder getRadiologyOrder(String accessionNumber, Patient patient) {

        // if no accession number, no order to associate with
        if (StringUtils.isBlank(accessionNumber)) {
            return null;
        }

        // try to fetch the order by accession number
        RadiologyOrder radiologyOrder = radiologyService.getRadiologyOrderByAccessionNumber(accessionNumber);

        // if there is an existing order, make sure the patients match
        // (note that we are allowing incoming obs with accession numbers that don't match anything in the system)
        if (radiologyOrder != null && !radiologyOrder.getPatient().equals(patient)) {
            throw new PacsIntegrationException("Cannot import message. Patient referenced in message different from patient attached to existing order.");

        }

        return radiologyOrder;
    }

    protected Concept getProcedure(String procedureCode) {
        Concept procedure = conceptService.getConceptByMapping(procedureCode, pacsIntegrationProperties.getProcedureCodesConceptSource().getName());

        if (procedure == null) {
            log.error("Unknown or missing procedure code specified in Radiology Report. Will still attempt to import report.");
        }

        return procedure;
    }

    protected String getSendingFacility() {
        return adminService.getGlobalProperty(PacsIntegrationConstants.GP_SENDING_FACILITY);
    }

    protected Location getLocationByName(String name) {
        return locationService.getLocation(name);
    }

    // this method is used to handle issues caused by slight time differences between OpenMRS and the source server
    // if the passed time is in the future, but less than 15 minutes in the future, return the current time (because
    // OpenMRS can't accept encounter dates in the future); if it is more than 15 minutes in the future, throw
    // an exception
    protected Date syncTimeWithCurrentServerTime(Date date) {

        DateTime now = new DateTime();
        DateTime dateTime = new DateTime(date);

        // just return the passed-in date if it is in the past
        if (dateTime.isBefore(now)) {
            return dateTime.toDate();
        }
        else {
            if (dateTime.minusMinutes(15).isBefore(now)) {
                return now.toDate();
            }
            else {
                throw new IllegalArgumentException("Date cannot be more than 15 minutes in the future.");
            }
        }
    }


    /**
     * Setters
     */

    public void setPatientService(PatientService patientService) {
        this.patientService = patientService;
    }

    public void setConceptService(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    public void setAdminService(AdministrationService adminService) {
        this.adminService = adminService;
    }

    public void setRadiologyService(RadiologyService radiologyService) {
        this.radiologyService = radiologyService;
    }

    public void setLocationService(LocationService locationService) {
        this.locationService = locationService;
    }

    public void setEmrApiProperties(EmrApiProperties emrApiProperties) {
        this.emrApiProperties = emrApiProperties;
    }

    public void setPacsIntegrationProperties(PacsIntegrationProperties pacsIntegrationProperties) {
        this.pacsIntegrationProperties = pacsIntegrationProperties;
    }

    public void setProviderService(ProviderService providerService) {
        this.providerService = providerService;
    }
}
