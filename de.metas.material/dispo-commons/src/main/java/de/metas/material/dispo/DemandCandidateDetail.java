package de.metas.material.dispo;

import java.util.Optional;

import de.metas.material.event.MaterialDemandDescr;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * metasfresh-material-dispo
 * %%
 * Copyright (C) 2017 metas GmbH
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
@Value
public class DemandCandidateDetail
{
	public static DemandCandidateDetail createOrNull(@NonNull final Optional<MaterialDemandDescr> materialDemandDescr)
	{
		if(!materialDemandDescr.isPresent())
		{
			return null;
		}
		return null;
	}
	
	
	int forecastLineId;
	
	int shipmentScheduleId;
	
	
	private final int orderLineId;

}
