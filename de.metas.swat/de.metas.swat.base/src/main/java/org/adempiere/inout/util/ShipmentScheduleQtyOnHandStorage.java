package org.adempiere.inout.util;

/*
 * #%L
 * de.metas.swat.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.adempiere.mm.attributes.api.StorageAttributesKeys;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.Adempiere;
import org.compiere.util.Util.ArrayKey;

import com.google.common.base.MoreObjects;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import de.metas.inoutcandidate.api.IShipmentScheduleEffectiveBL;
import de.metas.inoutcandidate.api.OlAndSched;
import de.metas.inoutcandidate.model.I_M_ShipmentSchedule;
import de.metas.material.dispo.commons.repository.AvailableStockResult;
import de.metas.material.dispo.commons.repository.MaterialMultiQuery;
import de.metas.material.dispo.commons.repository.MaterialMultiQuery.AggregationLevel;
import de.metas.material.dispo.commons.repository.MaterialQuery;
import de.metas.material.dispo.commons.repository.MaterialQuery.MaterialQueryBuilder;
import de.metas.material.dispo.commons.repository.StockRepository;
import de.metas.material.event.commons.StorageAttributesKey;
import lombok.NonNull;

/**
 * Loads stock details which are relevant to given {@link I_M_ShipmentSchedule}s.
 * Allows to change (in memory!) the qtyOnHand.
 */
public class ShipmentScheduleQtyOnHandStorage
{
	public static final ShipmentScheduleQtyOnHandStorage ofShipmentSchedules(final List<I_M_ShipmentSchedule> shipmentSchedules)
	{
		return new ShipmentScheduleQtyOnHandStorage(shipmentSchedules);
	}

	public static final ShipmentScheduleQtyOnHandStorage ofShipmentSchedule(@NonNull final I_M_ShipmentSchedule shipmentSchedule)
	{
		return new ShipmentScheduleQtyOnHandStorage(ImmutableList.of(shipmentSchedule));
	}

	public static final ShipmentScheduleQtyOnHandStorage ofOlAndScheds(final List<OlAndSched> lines)
	{
		final List<I_M_ShipmentSchedule> shipmentSchedules = lines.stream().map(OlAndSched::getSched).collect(ImmutableList.toImmutableList());
		return new ShipmentScheduleQtyOnHandStorage(shipmentSchedules);
	}

	// services
	private final transient IShipmentScheduleEffectiveBL shipmentScheduleEffectiveBL = Services.get(IShipmentScheduleEffectiveBL.class);

	private final List<ShipmentScheduleAvailableStockDetail> stockDetails;
	private final Map<ArrayKey, MaterialQuery> cachedMaterialQueries = new HashMap<>();

	private ShipmentScheduleQtyOnHandStorage(final List<I_M_ShipmentSchedule> shipmentSchedules)
	{
		final StockRepository stockRepository = Adempiere.getBean(StockRepository.class);
		stockDetails = createStockDetailsFromShipmentSchedules(shipmentSchedules, stockRepository);
	}

	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("storageRecords", stockDetails)
				.toString();
	}

	private final List<ShipmentScheduleAvailableStockDetail> createStockDetailsFromShipmentSchedules(final List<I_M_ShipmentSchedule> shipmentSchedules, final StockRepository stockRepository)
	{
		if (shipmentSchedules.isEmpty())
		{
			return ImmutableList.of();
		}
		final MaterialMultiQuery multiQuery = createMaterialMultiQueryOrNull(shipmentSchedules);
		if (multiQuery == null)
		{
			return ImmutableList.of();
		}

		final List<AvailableStockResult> availableStockResults = stockRepository.retrieveAvailableStock(multiQuery);
		return createStockDetails(availableStockResults);
	}

	private MaterialMultiQuery createMaterialMultiQueryOrNull(final List<I_M_ShipmentSchedule> shipmentSchedules)
	{
		final Set<MaterialQuery> materialQueries = shipmentSchedules.stream()
				.map(this::createMaterialQuery)
				.filter(Predicates.notNull())
				.collect(ImmutableSet.toImmutableSet());
		if (materialQueries.isEmpty())
		{
			return null;
		}

		return MaterialMultiQuery.builder()
				.queries(materialQueries)
				.aggregationLevel(AggregationLevel.NONE)
				.build();
	}

	public MaterialQuery createMaterialQuery(@NonNull final I_M_ShipmentSchedule sched)
	{
		// In case the DeliveryRule is Force, there is no point to load the storage, because it's not needed.
		// FIXME: make sure this works performance wise, then remove the commented code
		// final I_M_ShipmentSchedule shipmentSchedule = olAndSched.getSched();
		// final String deliveryRule = shipmentScheduleEffectiveValuesBL.getDeliveryRule(shipmentSchedule);
		// if (!X_M_ShipmentSchedule.DELIVERYRULE_Force.equals(deliveryRule))
		// return null;

		final TableRecordReference scheduleReference = TableRecordReference.ofReferenced(sched);

		//
		// Get the storage query from cache if available
		final ArrayKey materialQueryCacheKey = ArrayKey.of(
				scheduleReference.getTableName(),
				scheduleReference.getRecord_ID(),
				I_M_ShipmentSchedule.Table_Name,
				sched.getM_ShipmentSchedule_ID());

		final MaterialQuery materialQuery = cachedMaterialQueries.get(materialQueryCacheKey);
		if (materialQuery != null)
		{
			return materialQuery;
		}

		// Create storage query
		final int productId = sched.getM_Product_ID();
		final int warehouseId = shipmentScheduleEffectiveBL.getWarehouseId(sched);
		final int bpartnerId = shipmentScheduleEffectiveBL.getC_BPartner_ID(sched);
		final Date date = shipmentScheduleEffectiveBL.getPreparationDate(sched); // TODO: check with Mark if we shall use DeliveryDate.
		final MaterialQueryBuilder materialQueryBuilder = MaterialQuery.builder()
				.warehouseId(warehouseId)
				.productId(productId)
				.bpartnerId(bpartnerId)
				.date(date); 

		// Add query attributes
		final int asiId = sched.getM_AttributeSetInstance_ID();
		if (asiId > 0)
		{
			materialQueryBuilder.storageAttributesKey(StorageAttributesKeys.createAttributesKeyFromASI(asiId));
		}

		// Cache the storage query and return it
		cachedMaterialQueries.put(materialQueryCacheKey, materialQuery);
		return materialQuery;
	}

	private static final List<ShipmentScheduleAvailableStockDetail> createStockDetails(final List<AvailableStockResult> stockResults)
	{
		if (Check.isEmpty(stockResults))
		{
			return ImmutableList.of();
		}

		return stockResults.stream()
				.flatMap(stockResult -> stockResult.getResultGroups().stream())
				.map(ShipmentScheduleQtyOnHandStorage::createStockDetail)
				.collect(ImmutableList.toImmutableList());
	}

	private static ShipmentScheduleAvailableStockDetail createStockDetail(final AvailableStockResult.ResultGroup result)
	{
		return ShipmentScheduleAvailableStockDetail.builder()
				.productId(result.getProductId())
				.warehouseId(result.getWarehouseId())
				.bpartnerId(result.getBpartnerId())
				.storageAttributesKey(result.getStorageAttributesKey())
				.qtyOnHand(result.getQty())
				.build();
	}

	private Stream<ShipmentScheduleAvailableStockDetail> streamStockDetailsMatching(final MaterialQuery materialQuery)
	{
		return stockDetails
				.stream()
				.filter(stockDetail -> matching(materialQuery, stockDetail));
	}

	private static boolean matching(final MaterialQuery query, final ShipmentScheduleAvailableStockDetail stockDetail)
	{
		final List<Integer> productIds = query.getProductIds();
		if (!productIds.isEmpty() && !productIds.contains(stockDetail.getProductId()))
		{
			return false;
		}

		final Set<Integer> warehouseIds = query.getWarehouseIds();
		if (!warehouseIds.isEmpty() && !warehouseIds.contains(stockDetail.getWarehouseId()))
		{
			return false;
		}

		final int bpartnerId = query.getBpartnerId();
		if (bpartnerId > 0 && bpartnerId != stockDetail.getBpartnerId())
		{
			return false;
		}

		final List<StorageAttributesKey> storageAttributeKeys = query.getStorageAttributesKeys();
		if (!storageAttributeKeys.isEmpty() && !storageAttributeKeys.contains(stockDetail.getStorageAttributesKey()))
		{
			return false;
		}

		return true;
	}

	private boolean hasStockDetails()
	{
		return !stockDetails.isEmpty();
	}

	public List<ShipmentScheduleAvailableStockDetail> getStockDetailsMatching(@NonNull final I_M_ShipmentSchedule sched)
	{
		if (!hasStockDetails())
		{
			return Collections.emptyList();
		}

		final MaterialQuery materialQuery = createMaterialQuery(sched);
		return streamStockDetailsMatching(materialQuery)
				.collect(ImmutableList.toImmutableList());
	}

	// TODO: remove it
	public BigDecimal getQtyUnconfirmedShipmentsPerShipmentSchedule(final I_M_ShipmentSchedule sched)
	{
		return BigDecimal.ZERO;
	}
}
