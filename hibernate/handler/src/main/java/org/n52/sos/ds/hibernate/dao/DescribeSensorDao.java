/*
 * Copyright (C) 2012-2016 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *     - Apache License, version 2.0
 *     - Apache Software License, version 1.0
 *     - GNU Lesser General Public License, version 3
 *     - Mozilla Public License, versions 1.0, 1.1 and 2.0
 *     - Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public
 * License version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */
package org.n52.sos.ds.hibernate.dao;

import static org.n52.iceland.util.http.HTTPStatus.INTERNAL_SERVER_ERROR;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.n52.iceland.convert.Converter;
import org.n52.iceland.convert.ConverterException;
import org.n52.iceland.convert.ConverterRepository;
import org.n52.iceland.ds.ConnectionProvider;
import org.n52.iceland.exception.CodedException;
import org.n52.iceland.exception.ows.NoApplicableCodeException;
import org.n52.iceland.exception.ows.OwsExceptionReport;
import org.n52.iceland.lifecycle.Constructable;
import org.n52.iceland.ogc.ows.OwsServiceProvider;
import org.n52.iceland.ogc.ows.ServiceMetadataRepository;
import org.n52.iceland.ogc.sos.SosConstants;
import org.n52.iceland.util.LocalizedProducer;
import org.n52.series.db.beans.ProcedureEntity;
import org.n52.sos.coding.encode.ProcedureDescriptionFormatRepository;
import org.n52.sos.ds.hibernate.HibernateSessionHolder;
import org.n52.sos.ds.hibernate.dao.ProcedureDAO;
import org.n52.sos.ds.hibernate.dao.ValidProcedureTimeDAO;
import org.n52.sos.ds.hibernate.entities.Procedure;
import org.n52.sos.ds.hibernate.entities.TProcedure;
import org.n52.sos.ds.hibernate.entities.ValidProcedureTime;
import org.n52.sos.ds.hibernate.util.HibernateHelper;
import org.n52.sos.ds.hibernate.util.procedure.HibernateProcedureConverter;
import org.n52.sos.ogc.sensorML.SensorMLConstants;
import org.n52.sos.ogc.sos.SosProcedureDescription;
import org.n52.sos.request.DescribeSensorRequest;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;


public class DescribeSensorDao implements org.n52.sos.ds.dao.DescribeSensorDao, Constructable {
    
    private HibernateSessionHolder sessionHolder;

    private ServiceMetadataRepository serviceMetadataRepository;
    private HibernateProcedureConverter procedureConverter;
    private ConverterRepository converterRepository;
    private ProcedureDescriptionFormatRepository procedureDescriptionFormatRepository;

    @Inject
    public void setConverterRepository(ConverterRepository repo) {
        this.converterRepository = repo;
    }

    @Inject
    public void setProcedureDescriptionFormatRepository(
            ProcedureDescriptionFormatRepository repo) {
        this.procedureDescriptionFormatRepository = repo;
    }

    @Inject
    public void setServiceMetadataRepository(ServiceMetadataRepository repo) {
        this.serviceMetadataRepository = repo;
    }

    @Inject
    public void setConnectionProvider(ConnectionProvider connectionProvider) {
        this.sessionHolder = new HibernateSessionHolder(connectionProvider);
    }

    @Override
    public void init() {
        LocalizedProducer<OwsServiceProvider> serviceProvider
                = this.serviceMetadataRepository.getServiceProviderFactory(SosConstants.SOS);
        this.procedureConverter = new HibernateProcedureConverter(serviceProvider);
    }



    @Override
    public List<SosProcedureDescription> querySensorDescriptions(DescribeSensorRequest request) throws OwsExceptionReport {
        Session session = null;
        try {
            session = sessionHolder.getSession();
            return queryDescriptions(request, session);
        } catch (final HibernateException he) {
            throw new NoApplicableCodeException().causedBy(he).withMessage(
                    "Error while querying data for DescribeSensor document!");
        } finally {
            sessionHolder.returnSession(session);
        }
    }
    
    private List<SosProcedureDescription> queryDescriptions(DescribeSensorRequest request, Session session) throws OwsExceptionReport {
        List<SosProcedureDescription> descriptions = new LinkedList<SosProcedureDescription>();
        if (HibernateHelper.isEntitySupported(ValidProcedureTime.class)) {
            descriptions.addAll(getProcedureDescriptions(request, session));
        } else {
            descriptions.add(getProcedureDescription(request, session));
        }
        return descriptions;
    }
    
    /**
     * Get procedure description for non transactional SOS
     *
     * @param request
     *            DescribeSensorRequest request
     * @param session
     *            Hibernate session
     * @return Matched procedure description
     * @throws OwsExceptionReport
     *             If an error occurs
     */
    private SosProcedureDescription getProcedureDescription(DescribeSensorRequest request, Session session)
            throws OwsExceptionReport {
        final Procedure procedure = new ProcedureDAO().getProcedureForIdentifier(request.getProcedure(), session);
        if (procedure == null) {
            throw new NoApplicableCodeException().causedBy(
                    new IllegalArgumentException("Parameter 'procedure' should not be null!")).setStatus(
                            INTERNAL_SERVER_ERROR);
        }
        return procedureConverter.createSosProcedureDescription(procedure,
                                                                request.getProcedureDescriptionFormat(),
                                                                request.getVersion(),
                                                                request.getRequestedLocale(),
                                                                session);
    }

    /**
     * @param request
     *            DescribeSensorRequest request
     * @param session
     *            Hibernate session
     * @return Matching procedure descriptions
     * @throws OwsExceptionReport
     *             If an error occurs
     */
    private List<SosProcedureDescription> getProcedureDescriptions(DescribeSensorRequest request, Session session) throws OwsExceptionReport {
        Set<String> possibleProcedureDescriptionFormats =
                getPossibleProcedureDescriptionFormats(request.getProcedureDescriptionFormat());
        final TProcedure procedure =
                new ProcedureDAO().getTProcedureForIdentifier(request.getProcedure(),
                                                              possibleProcedureDescriptionFormats, request.getValidTime(), session);
        List<SosProcedureDescription> list = Lists.newLinkedList();
        if (procedure != null) {
            List<ValidProcedureTime> validProcedureTimes =
                    new ValidProcedureTimeDAO().getValidProcedureTimes(procedure, possibleProcedureDescriptionFormats,
                                                                                  request.getValidTime(), session);
            for (ValidProcedureTime validProcedureTime : validProcedureTimes) {
                SosProcedureDescription sosProcedureDescription
                        = procedureConverter.createSosProcedureDescriptionFromValidProcedureTime(
                                procedure,
                                request.getProcedureDescriptionFormat(),
                                validProcedureTime,
                                request.getVersion(),
                                request.getRequestedLocale(),
                                session);
                list.add(convertProcedureDescription(sosProcedureDescription, request));
            }
        } else {
            SosProcedureDescription procedureDescription = getProcedureDescription(request, session);
            if (procedureDescription != null) {
                list.add(procedureDescription);
            } else {
                if (!request.isSetValidTime()) {
                    throw new NoApplicableCodeException().causedBy(
                            new IllegalArgumentException("Parameter 'procedure' should not be null!")).setStatus(
                                    INTERNAL_SERVER_ERROR);
                }
            }
        }
        return list;
    }

    /**
     * Get possible procedure description formats for this procedure description
     * format. More precise, are there converter available.
     *
     * @param procedureDescriptionFormat
     *            Procedure description format to check
     * @return All possible procedure description formats
     */
    private Set<String> getPossibleProcedureDescriptionFormats(String procedureDescriptionFormat) {
        Set<String> possibleFormats = checkForUrlVsMimeType(procedureDescriptionFormat);
        String matchingPdf = getProcedureDescriptionFormatMatchingString(procedureDescriptionFormat);
        this.procedureDescriptionFormatRepository.getAllProcedureDescriptionFormats().values().stream()
                .flatMap(Set::stream)
                .filter(pdf -> matchingPdf.equals(getProcedureDescriptionFormatMatchingString(pdf)))
                .forEach(possibleFormats::add);
        possibleFormats.addAll(converterRepository.getFromNamespaceConverterTo(procedureDescriptionFormat));
        return possibleFormats;
    }

    /**
     * Get procedure description format matching String, to lower case replace
     * \s
     *
     * @param procedureDescriptionFormat
     *            Procedure description formats to format
     * @return Formatted procedure description format String
     */
    private String getProcedureDescriptionFormatMatchingString(String procedureDescriptionFormat) {
        // match against lowercase string, ignoring whitespace
        return procedureDescriptionFormat.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    }


    private Set<String> checkForUrlVsMimeType(String procedureDescriptionFormat) {
        Set<String> possibleFormats = Sets.newHashSet();
        possibleFormats.add(procedureDescriptionFormat);
        if (SensorMLConstants.SENSORML_OUTPUT_FORMAT_MIME_TYPE.equalsIgnoreCase(procedureDescriptionFormat)) {
            possibleFormats.add(SensorMLConstants.SENSORML_OUTPUT_FORMAT_URL);
         } else if (SensorMLConstants.SENSORML_OUTPUT_FORMAT_URL.equalsIgnoreCase(procedureDescriptionFormat)) {
             possibleFormats.add(SensorMLConstants.SENSORML_OUTPUT_FORMAT_MIME_TYPE);
         }
        return possibleFormats;
    }

    private SosProcedureDescription convertProcedureDescription(
            SosProcedureDescription procedureDescription,
                                                                DescribeSensorRequest request)
            throws CodedException {
        if (!checkForUrlVsMimeType(procedureDescription.getDescriptionFormat()).contains(request.getProcedureDescriptionFormat())) {
            Converter<SosProcedureDescription, SosProcedureDescription> converter =
                    converterRepository.getConverter(procedureDescription.getDescriptionFormat(), request.getProcedureDescriptionFormat());
            if (converter != null) {
                try {
                    return converter.convert(procedureDescription);
                } catch (ConverterException e) {
                    throw new NoApplicableCodeException().causedBy(e).withMessage(
                            "Error while converting procedureDescription!");
                }
            } else {
                throw new NoApplicableCodeException().withMessage("No converter (%s -> %s) found!",
                        procedureDescription.getDescriptionFormat(), request.getProcedureDescriptionFormat());
            }
        }
        return procedureDescription;
    }

}
