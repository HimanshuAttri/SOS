/*
 * Copyright (C) 2012-2017 52°North Initiative for Geospatial Open Source
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
package org.n52.sos.ds.hibernate.cache.base;

import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.n52.shetland.ogc.ows.exception.OwsExceptionReport;
import org.n52.shetland.util.CollectionHelper;
import org.n52.sos.ds.hibernate.cache.AbstractThreadableDatasourceCacheUpdate;
import org.n52.sos.ds.hibernate.cache.DatasourceCacheUpdateHelper;
import org.n52.sos.ds.hibernate.dao.DaoFactory;
import org.n52.sos.ds.hibernate.dao.ObservablePropertyDAO;
import org.n52.sos.ds.hibernate.dao.ObservationConstellationDAO;
import org.n52.sos.ds.hibernate.dao.OfferingDAO;
import org.n52.sos.ds.hibernate.dao.ProcedureDAO;
import org.n52.sos.ds.hibernate.entities.ObservableProperty;
import org.n52.sos.ds.hibernate.entities.ObservationConstellation;
import org.n52.sos.ds.hibernate.util.HibernateHelper;
import org.n52.sos.ds.hibernate.util.ObservationConstellationInfo;

/**
 *
 * @author <a href="mailto:c.autermann@52north.org">Christian Autermann</a>
 *
 * @since 4.0.0
 */
public class ObservablePropertiesCacheUpdate extends AbstractThreadableDatasourceCacheUpdate {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservablePropertiesCacheUpdate.class);

    private final DaoFactory daoFactory;

    public ObservablePropertiesCacheUpdate(DaoFactory daoFactory) {
        this.daoFactory = daoFactory;
    }

    @Override
    public void execute() {
        LOGGER.debug("Executing ObservablePropertiesCacheUpdate");
        startStopwatch();
        ObservablePropertyDAO observablePropertyDAO = daoFactory.getObservablePropertyDAO();
        Map<ObservableProperty, Collection<ObservableProperty>> observablePropertyHierarchy = observablePropertyDAO.getObservablePropertyHierarchy(getSession());
//        Set<String> childObservableProperties = new HashSet<>(observablePropertyHierarchy.size());
//
//        for (Collection<ObservableProperty> children1: observablePropertyHierarchy.values()) {
//            for (ObservableProperty observableProperty1 : children1) {
//                childObservableProperties.add(observableProperty1.getIdentifier());
//            }
//        }


        // if ObservationConstellation is supported load them all at once,
        // otherwise query obs directly
        if (HibernateHelper.isEntitySupported(ObservationConstellation.class)) {
            ObservationConstellationDAO observationConstellationDAO = daoFactory.getObservationConstellationDAO();
            Map<String, Collection<ObservationConstellationInfo>> ociMap = ObservationConstellationInfo.mapByObservableProperty(observationConstellationDAO.getObservationConstellationInfo(getSession()));

            for (ObservableProperty observableProperty : observablePropertyHierarchy.keySet()) {
                String observablePropertyIdentifier = observableProperty.getIdentifier();
                Collection<ObservableProperty> children = observablePropertyHierarchy.get(observableProperty);
                boolean isParent = !children.isEmpty();

                if (observableProperty.isSetName()) {
                    getCache().addObservablePropertyIdentifierHumanReadableName(observablePropertyIdentifier, observableProperty.getName());
                }

                if (!observableProperty.isHiddenChild()) {
                    Collection<ObservationConstellationInfo> ocis = ociMap.get(observablePropertyIdentifier);
                    if (CollectionHelper.isNotEmpty(ocis)) {
                        getCache().setOfferingsForObservableProperty(observablePropertyIdentifier, DatasourceCacheUpdateHelper.getAllOfferingIdentifiersFromObservationConstellationInfos(ocis));
                        getCache().setProceduresForObservableProperty(observablePropertyIdentifier, DatasourceCacheUpdateHelper.getAllProcedureIdentifiersFromObservationConstellationInfos(ocis));
                    }
                }

                if (isParent) {
                    getCache().addCompositePhenomenon(observablePropertyIdentifier);
                    for (ObservableProperty child : children) {
                        getCache().addCompositePhenomenonForObservableProperty(child.getIdentifier(), observablePropertyIdentifier);
                        getCache().addObservablePropertyForCompositePhenomenon(observablePropertyIdentifier, child.getIdentifier());
                    }

                }
            }
        } else {
            OfferingDAO offeringDAO = daoFactory.getOfferingDAO();
            ProcedureDAO procedureDAO = daoFactory.getProcedureDAO();
            for (ObservableProperty op : observablePropertyHierarchy.keySet()) {
                String observableProperty = op.getIdentifier();
                try {
                    getCache().setOfferingsForObservableProperty(observableProperty, offeringDAO.getOfferingIdentifiersForObservableProperty(observableProperty, getSession()));
                } catch (OwsExceptionReport e) {
                    getErrors().add(e);
                }
                try {
                    getCache().setProceduresForObservableProperty(observableProperty, procedureDAO.getProcedureIdentifiersForObservableProperty(observableProperty, getSession()));
                } catch (OwsExceptionReport owse) {
                    getErrors().add(owse);
                }
            }
        }
        LOGGER.debug("Executing ObservablePropertiesCacheUpdate ({})", getStopwatchResult());
    }
}
