/*******************************************************************************
 * Gisgraphy Project 
 *  
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *  
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *    Lesser General Public License for more details.
 *  
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA
 *  
 *   Copyright 2008  Gisgraphy project 
 * 
 *   David Masclet <davidmasclet@gisgraphy.com>
 ******************************************************************************/
package com.gisgraphy.geocoding;

import static com.gisgraphy.helper.StringHelper.isEmptyString;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gisgraphy.addressparser.Address;
import com.gisgraphy.addressparser.AddressQuery;
import com.gisgraphy.addressparser.AddressResultsDto;
import com.gisgraphy.addressparser.IAddressParserService;
import com.gisgraphy.addressparser.StructuredAddressQuery;
import com.gisgraphy.addressparser.commons.GeocodingLevels;
import com.gisgraphy.addressparser.exception.AddressParserException;
import com.gisgraphy.addressparser.format.BasicAddressFormater;
import com.gisgraphy.addressparser.format.DisplayMode;
import com.gisgraphy.compound.Decompounder;
import com.gisgraphy.domain.geoloc.entity.Adm;
import com.gisgraphy.domain.geoloc.entity.City;
import com.gisgraphy.domain.geoloc.entity.CitySubdivision;
import com.gisgraphy.domain.geoloc.entity.Street;
import com.gisgraphy.domain.valueobject.Constants;
import com.gisgraphy.domain.valueobject.GisgraphyConfig;
import com.gisgraphy.domain.valueobject.HouseNumberAddressDto;
import com.gisgraphy.domain.valueobject.Output;
import com.gisgraphy.domain.valueobject.Output.OutputStyle;
import com.gisgraphy.domain.valueobject.Pagination;
import com.gisgraphy.fulltext.FullTextSearchEngine;
import com.gisgraphy.fulltext.FulltextQuery;
import com.gisgraphy.fulltext.FulltextQuerySolrHelper;
import com.gisgraphy.fulltext.FulltextResultsDto;
import com.gisgraphy.fulltext.SmartStreetDetection;
import com.gisgraphy.fulltext.SolrResponseDto;
import com.gisgraphy.fulltext.SolrResponseDtoDistanceComparator;
import com.gisgraphy.geoloc.ZipcodeNormalizer;
import com.gisgraphy.helper.CountryDetector;
import com.gisgraphy.helper.CountryDetectorDto;
import com.gisgraphy.helper.GeolocHelper;
import com.gisgraphy.helper.StringHelper;
import com.gisgraphy.importer.ImporterConfig;
import com.gisgraphy.importer.LabelGenerator;
import com.gisgraphy.reversegeocoding.AddressHelper;
import com.gisgraphy.serializer.UniversalSerializer;
import com.gisgraphy.serializer.common.UniversalSerializerConstant;
import com.gisgraphy.service.IStatsUsageService;
import com.gisgraphy.stats.StatsUsageType;
import com.gisgraphy.street.HouseNumberDto;
import com.gisgraphy.street.HouseNumberUtil;
import com.vividsolutions.jts.geom.Point;

/**
 * 
 * Geocode internationnal address via gisgraphy services
 * 
 * @author <a href="mailto:david.masclet@gisgraphy.com">David Masclet</a>
 * 
 */
@Service
public class GeocodingService implements IGeocodingService {
	

	private static final String FUZZY_ACTIVE = "fuzzy:active";
	//private static final Pattern GERMAN_SYNONYM_PATTEN = Pattern.compile("(?<=\\w)(str\\b)[\\.]?",Pattern.CASE_INSENSITIVE);
	
	private static final int INTERPOLATION_CURVE_TOLERANCE = 45;
	private IStatsUsageService statsUsageService;
	private ImporterConfig importerConfig;
	private IAddressParserService addressParser;
	private FullTextSearchEngine fullTextSearchEngine;
	private GisgraphyConfig gisgraphyConfig;
	
	private LabelGenerator labelGenerator = LabelGenerator.getInstance();
	private BasicAddressFormater addressFormater = BasicAddressFormater.getInstance();
	
	CountryDetector countryDetector = new CountryDetector();
	
	SmartStreetDetection smartStreetDetection = new SmartStreetDetection();

	public final static int ACCEPT_DISTANCE_BETWEEN_CITY_AND_STREET = 15000;
	
	public final static Output LONG_OUTPUT = Output.withDefaultFormat().withStyle(OutputStyle.LONG);
	public final static Output MEDIUM_OUTPUT = Output.withDefaultFormat().withStyle(OutputStyle.MEDIUM);
	public final static Output FULL_OUTPUT = Output.withDefaultFormat().withStyle(OutputStyle.FULL);
	public final static Output DEFAULT_OUTPUT = LONG_OUTPUT;
	
	
	public final static Pagination ONE_RESULT_PAGINATION = Pagination.paginate().from(0).to(1);
	public final static Pagination FIVE_RESULT_PAGINATION = Pagination.paginate().from(0).to(5);
	public final static Pagination TEN_RESULT_PAGINATION = Pagination.paginate().from(0).to(10);
	public final static SolrResponseDtoDistanceComparator comparator = new SolrResponseDtoDistanceComparator();
	
	
	
	
	Decompounder decompounder = new Decompounder();

	/**
	 * The logger
	 */
	protected static final Logger logger = LoggerFactory.getLogger(GeocodingService.class);

	public static final Float SCORE_THRESHOLD_FUZZY = 3F;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.gisgraphy.geocoding.IGeocodingService#geocodeAndSerialize(com.gisgraphy
	 * .addressparser.AddressQuery, java.io.OutputStream)
	 */
	public void geocodeAndSerialize(AddressQuery query, OutputStream outputStream) throws GeocodingException {
		if (query == null) {
			throw new GeocodingException("Can not geocode a null query");
		}
		if (outputStream == null) {
			throw new GeocodingException("Can not serialize into a null outputStream");
		}
		AddressResultsDto geolocResultsDto = geocode(query);
		Map<String, Object> extraParameter = new HashMap<String, Object>();
		// extraParameter.put(GeolocResultsDtoSerializer.START_PAGINATION_INDEX_EXTRA_PARAMETER,
		// query.getFirstPaginationIndex());
		extraParameter.put(UniversalSerializerConstant.CALLBACK_METHOD_NAME, query.getCallback());
		UniversalSerializer.getInstance().write(outputStream, geolocResultsDto, false, extraParameter, query.getFormat());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.gisgraphy.geocoding.IGeocodingService#geocodeToString(com.gisgraphy
	 * .addressparser.AddressQuery)
	 */
	public String geocodeToString(AddressQuery query) throws GeocodingException {
		if (query == null) {
			throw new GeocodingException("Can not geocode a null query");
		}
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		geocodeAndSerialize(query, outputStream);
		try {
			return outputStream.toString(Constants.CHARSET);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("unknow encoding " + Constants.CHARSET);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.gisgraphy.geocoding.IGeocodingService#geocode(java.lang.String)
	 */
	public AddressResultsDto geocode(AddressQuery query) throws GeocodingException {
		if (query == null) {
			throw new GeocodingException("Can not geocode a null query");
		}
		logger.info(query.toString());
		String countryCode = query.getCountry();
		if (countryCode !=null  && countryCode.trim().length() != 2) {
			throw new GeocodingException("countrycode should have two letters : " + countryCode);
		}
		if (query instanceof StructuredAddressQuery){
			Address address = ((StructuredAddressQuery)query).getStructuredAddress();
			if (logger.isDebugEnabled()) {
				logger.debug("structured address to geocode : '" + address + "' for country code : " + countryCode);
			}
			AddressResultsDto addressResultsDto = geocode(address, countryCode);
			if (shouldSetParseAddress(query)){
				addressResultsDto.setParsedAddress(address);
			}
			return AddressHelper.limitNbResult(addressResultsDto,query.getLimitNbResult());
		}
		String rawAddress = query.getAddress();
		Long startTime = System.currentTimeMillis();
		if (isEmptyString(rawAddress)) {
			throw new GeocodingException("Can not geocode a null or empty address");
		}
	//	rawAddress = prepareQuery(rawAddress);
		//always search for country to remove it
			CountryDetectorDto detectorDto = countryDetector.detectAndRemoveCountry(rawAddress);
			if (detectorDto != null && detectorDto.getCountryCode()!=null){
				if (detectorDto.getAddress()!= null && !detectorDto.getAddress().trim().equals("")){
					rawAddress = detectorDto.getAddress();
					if (countryCode ==null){
						countryCode = detectorDto.getCountryCode();
					}
				} else {
					//it is a country we use the raw address without countrycode.
					List<SolrResponseDto> countries = findInText(rawAddress,null,null,com.gisgraphy.fulltext.Constants.ONLY_COUNTRY_PLACETYPE, false, null);
					AddressResultsDto results = buildAddressResultDtoFromSolrResponseDtoCountry(countries);
					return AddressHelper.limitNbResult(results,query.getLimitNbResult());
					
				}
			//}
		}
		if (countryCode !=null  && countryCode.trim().length() != 2) {
			throw new GeocodingException("countrycode should have two letters : " + countryCode);
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("Raw address to geocode : '" + rawAddress + "' for country code : " + countryCode);
		}
		AddressQuery addressQuery = new AddressQuery(rawAddress, countryCode);
		AddressResultsDto addressResultDto = null;
		logger.debug("is postal address : " +query.isPostal());
		boolean needParsing = needParsing(query.getAddress());
		if ((gisgraphyConfig.useAddressParserWhenGeocoding || query.isPostal()) && needParsing) {
			try {
				logger.debug("address parser is enabled");
				addressResultDto = addressParser.execute(addressQuery);
			} catch (AddressParserException e) {
				logger.error("An error occurs during parsing of address" + e.getMessage(), e);
			}
		} else {
			logger.debug("won't parse "+rawAddress);
		}
		if (addressResultDto != null && addressResultDto.getResult().size() >= 1 && isGeocodable(addressResultDto.getResult().get(0))) {
			if (logger.isDebugEnabled()) {
				logger.debug("successfully parsed address : " + rawAddress + " : " + addressResultDto.getResult().get(0));
			}
			Address address = addressResultDto.getResult().get(0);
			AddressResultsDto addressesDto = geocode(address, countryCode);
			if (shouldSetParseAddress(query)){
				addressesDto.setParsedAddress(address);
			}
			return AddressHelper.limitNbResult(addressesDto,query.getLimitNbResult());
		} else
			//if (importerConfig.isOpenStreetMapFillIsIn()) 
			{
		//	logger.debug("is_in is active");
			statsUsageService.increaseUsage(StatsUsageType.GEOCODING);
			AddressResultsDto results;
			rawAddress = StringHelper.prepareQuery(rawAddress);
			HouseNumberAddressDto houseNumberAddressDto = GeocodingHelper.findHouseNumber(rawAddress, countryCode);
			String newAddress = rawAddress;
			
			String houseNumber = null;
			if (houseNumberAddressDto != null){
				houseNumber = houseNumberAddressDto.getHouseNumber();
				newAddress = houseNumberAddressDto.getAddressWithoutHouseNumber();
			} 
			List<String> streettypes = smartStreetDetection.getStreetTypes(newAddress);
			for (String streettype : streettypes){
				logger.info("found street type : "+streettype);
			}
				boolean smartstreetdetected = false;
			String alternativeGermanAddress =null;
			if (streettypes!=null && streettypes.size()==1){
				smartstreetdetected = true;
				if (Decompounder.isDecompoudCountryCode(countryCode) || decompounder.isDecompoundName(newAddress)){
					logger.error("find specific german address");
					alternativeGermanAddress = decompounder.getOtherFormatForText(newAddress);
					logger.error("alternativeGermanAddress= "+alternativeGermanAddress);
					//alternativeGermanAddress = replaceGermanSynonyms(alternativeGermanAddress);
					newAddress = StringHelper.expandStreetType(newAddress, null);
					logger.error("new rawAddress with synonyms ="+newAddress);
					//logger.error("new alternative with synonyms ="+alternativeGermanAddress);
					
				} else {
					logger.error("don't proces specific german address");
				}
			}
			results = doSearch(newAddress,alternativeGermanAddress, countryCode, 
					needParsing,houseNumber, false, query.getPoint(), query.getRadius(), smartstreetdetected);



			//try in fuzzy
			if (query.isFuzzy() && (results.getNumFound()==0 || (results.getResult().size()>0 && results.getResult().get(0).getScore()< SCORE_THRESHOLD_FUZZY ))){
				results = doSearch(newAddress,alternativeGermanAddress, countryCode,
						needParsing,houseNumber, true, query.getPoint(), query.getRadius(), smartstreetdetected);
			}
					
				
			Long endTime = System.currentTimeMillis();
			long qTime = endTime - startTime;
			results.setQTime(qTime);
			logger.info("geocoding of "+query + " and country="+countryCode+" took " + (qTime) + " ms and returns "
					+ results.getNumFound() + " results");
			return AddressHelper.limitNbResult(results,query.getLimitNbResult());
		}
	}


	
	
	/*private String prepareQueryConcatenate(String rawAddress) {
		String newAddress = rawAddress;
		newAddress = newAddress.toLowerCase();
		newAddress = newAddress.replaceAll(" (?:straße|str|strasse)\\b", "straße").replaceAll("[\\s]+", " ");
		logger.error("newAddress concatenate ="+newAddress);
		return newAddress;
	}*/

	

	protected AddressResultsDto doSearch( String rawaddress,String alternativeStreetAddress,
			String countryCode, boolean needParsing,String houseNumber, boolean fuzzy, Point point, Double radius, boolean smartStreetDetected) {
		AddressResultsDto results;
		List<SolrResponseDto> exactMatches  ;
		if (!smartStreetDetected){
			 exactMatches = doSearchExact(rawaddress,
					countryCode, fuzzy, point, radius, null);
		} else {
			//bypass exact search
			 exactMatches  =new ArrayList<SolrResponseDto>();
		}
	
			//have been probably found by exact match, so we search for address and so a street*/
			if (!needParsing && exactMatches!=null && exactMatches.size() >=1){
				//only one word and exact match ok
				results = buildAddressResultDtoFromSolrResponseDto(exactMatches, houseNumber);
			} else {
				
				List<SolrResponseDto> fulltextResultsDto = doSearchStreet(
						rawaddress, countryCode, fuzzy, point, radius);
				if(alternativeStreetAddress!=null){
					logger.error("will search for altenative german Address : "+alternativeStreetAddress);
					List<SolrResponseDto> alternativeResults = doSearchStreet(
							alternativeStreetAddress, countryCode, fuzzy, point, radius);
					if (fulltextResultsDto.size()==0 ||(alternativeResults!=null && alternativeResults.size()>0 && fulltextResultsDto!=null && fulltextResultsDto.size() > 0 
							&& alternativeResults.get(0)!=null && fulltextResultsDto.get(0)!=null
							&& alternativeResults.get(0).getScore()>fulltextResultsDto.get(0).getScore())){
						logger.error("alternative results score is higher");
						fulltextResultsDto = alternativeResults;
					}
				} 
				
				if (fulltextResultsDto!=null){
					exactMatches.addAll(fulltextResultsDto);
				}
				
				logger.error("-------------------merged--------------------------");
				if (exactMatches!=null){
				for (SolrResponseDto result: exactMatches){
					logger.error(result.getScore()+" : "+(result.getOpenstreetmap_id()==null?result.getFeature_id():result.getOpenstreetmap_id())+"-"+result.getFully_qualified_name());
				}
				}
				results = buildAddressResultDtoFromSolrResponseDto(exactMatches, houseNumber);
			}
			if (fuzzy){
				results.setMessage(FUZZY_ACTIVE);
			}
		return results;
	}

	

	protected List<SolrResponseDto> doSearchStreet(String rawaddress,
			String countryCode, boolean fuzzy, Point point, Double radius) {
		logger.debug("will search for street "+(fuzzy?" in fuzzy mode":" in strict mode"));
		List<SolrResponseDto> fulltextResultsDto = findStreetInText(rawaddress, countryCode, point, fuzzy, radius); //we search for street because we think that it is not a city nor an adm that 
		//List<SolrResponseDto> mergedResults = mergeSolrResponseDto(exactMatches, fulltextResultsDto);
		return fulltextResultsDto;
	}

	protected List<SolrResponseDto> doSearchExact(String rawaddress,
			String countryCode, boolean fuzzy, Point point, Double radius, Class[] placetype) {
		logger.debug("will search for exact match "+(fuzzy?"in fuzzy mode":" in strict mode"));
		List<SolrResponseDto> exactMatches = findExactMatches(rawaddress, countryCode, fuzzy, point, radius, placetype);
		//filter result where name is not the same
		if (exactMatches!=null){
			List<SolrResponseDto> filterResults = new ArrayList<SolrResponseDto>();
			for (SolrResponseDto result: exactMatches){
				boolean added= false;
				if(result!=null && result.getName()!=null && (StringHelper.isSameName(rawaddress, result.getName(),1) || ZipcodeNormalizer.containsGBPostCode(rawaddress))){
					filterResults.add(result);
					added =true;
					logger.error("filter same name, adding "+(result.getOpenstreetmap_id()!=null?result.getOpenstreetmap_id():result.getFeature_id())+"-"+result.getName()+" / "+result.getFully_qualified_name() );
				}
				else if (!added){
					for (String nameAlternate : result.getName_alternates()){
						if (nameAlternate!=null && StringHelper.isSameName(rawaddress, nameAlternate,1)){
							logger.error("filter same name, adding alternate "+(result.getOpenstreetmap_id()!=null?result.getOpenstreetmap_id():result.getFeature_id())+" :  "+nameAlternate+" / "+result.getFully_qualified_name() );
							filterResults.add(result);
							added=true;
							break;
						}
					}
					
				}
				if (!added){
					logger.error("filter same name, ignoring :"+(result.getOpenstreetmap_id()!=null?result.getOpenstreetmap_id():result.getFeature_id())+"-"+result.getName()+" / "+result.getFully_qualified_name() );
				}
			}
			if (!filterResults.isEmpty()){
				exactMatches = filterResults;
			}
			
		}
		logger.error("-------------------exact--------------------------");
		if (exactMatches!=null){
		for (SolrResponseDto result: exactMatches){
			logger.error(result.getScore()+" : "+(result.getOpenstreetmap_id()==null?result.getFeature_id():result.getOpenstreetmap_id())+"-"+result.getFully_qualified_name());
		}
		}
		return exactMatches;
	}

	protected boolean needParsing(String query) {
		if (query !=null){
			String str = query.trim();
			return str.length() > 0 && (str.indexOf(" ") != -1 || str.indexOf(",") != -1 || str.indexOf(";") != -1);
		}
		return false;
	}
	
	protected boolean shouldSetParseAddress(AddressQuery query){
	if (query !=null && query.getParsedAddressUnlockKey()!=0 && importerConfig.getParsedAddressUnlockKey() !=0 
			&& query.getParsedAddressUnlockKey()==importerConfig.getParsedAddressUnlockKey()){
		return true;
	}
	return false;
	}

	protected boolean isGeocodable(Address address) {
		if (isEmptyString(address.getStreetName()) && isEmptyString(address.getState()) && isEmptyString(address.getCity()) && isEmptyString(address.getZipCode()) && isEmptyString(address.getPostTown()) && isEmptyString(address.getCitySubdivision())) {
			logger.info(address+" is no geocodable");
			return false;
		}
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.gisgraphy.geocoding.IGeocodingService#geocode(com.gisgraphy.addressparser
	 * .Address)
	 */
	public AddressResultsDto geocode(Address address, String countryCode) throws GeocodingException {
		if (address == null) {
			throw new GeocodingException("Can not geocode a null address");
		}
		if (countryCode!=null &&  countryCode.trim().length() != 2) {
			throw new GeocodingException("wrong countrycode : " + countryCode);
		}
		if (isIntersection(address)) {
			throw new GeocodingException("street intersection is not managed yet");
		}
		if (!isGeocodable(address)) {
			throw new GeocodingException("City, street name, posttown and zip is not set, we got too less informations to geocode ");
		}
		if (address.getCountryCode()==null && countryCode!=null){
			//usefull for formater
			address.setCountryCode(countryCode);
		}
		statsUsageService.increaseUsage(StatsUsageType.GEOCODING);
		Long startTime = System.currentTimeMillis();
		
		AddressResultsDto results;
		List<SolrResponseDto> streets = new ArrayList<SolrResponseDto>();
		String houseNumber = address.getHouseNumber();
		address.setHouseNumber(null);
		address.setHouseNumberInfo(null);
		String rawAddress = addressFormater.getEnvelopeAddress(address, DisplayMode.COMMA);
		boolean fuzzy = false;
		if (rawAddress!=null){
			if (!isEmptyString(address.getStreetName())){
				//search for street
				//buildAddress string
				streets = doSearchStreet(rawAddress,countryCode,false,null,null);
				if (streets==null || streets.size()==0){
					//retry in fuzzy
					streets = doSearchStreet(rawAddress,countryCode,true,null,null);
					fuzzy = true;
				} 

			} else {
				//not a street, search for city, Adm, subdivision
				Class[] placetype = com.gisgraphy.fulltext.Constants.CITY_CITYSUB_ADM_PLACETYPE;
				if(address!=null){
					if (address.getCity()!=null || address.getZipCode()!=null ||address.getCitySubdivision()!=null){
						placetype= com.gisgraphy.fulltext.Constants.CITY_AND_CITYSUBDIVISION_PLACETYPE;
					}
					if (address.getState()!=null && (address.getCity()!=null && address.getZipCode()!=null && address.getCitySubdivision()!=null)){
						placetype=com.gisgraphy.fulltext.Constants.ONLY_ADM_PLACETYPE;
					}
				}
				streets =  doSearchExact(rawAddress, countryCode, false, null, null, placetype);
				if (streets==null || streets.size()==0){
					fuzzy = true;
					streets =  doSearchExact(rawAddress, countryCode, true, null, null, placetype);
				}
			}
		}
		results = AddressHelper.limitNbResult(buildAddressResultDtoFromSolrResponseDto(streets, houseNumber),10);
		if (fuzzy){
			results.setMessage(FUZZY_ACTIVE);
		}
		
			Long endTime = System.currentTimeMillis();
			long qTime = endTime - startTime;
			results.setQTime(qTime);
			logger.info("geocoding of "+address + " and country="+countryCode+" took " + (qTime) + " ms and returns "
					+ results.getNumFound() + " results");
			return results;
		//}
	}


	protected HouseNumberDtoInterpolation searchHouseNumber(Integer houseNumberToFindAsInt, List<HouseNumberDto> houseNumbersList,String countryCode, boolean doInterpolation) { //TODO pass the house as int directly
		if(houseNumberToFindAsInt==null || houseNumbersList==null || houseNumbersList.size()==0){
			logger.info("no house number to search : ");
			return null;
		}
		Integer nearestLower = null;
		Integer nearestUpper = null;
		HouseNumberDto nearestHouseLower = null;
		HouseNumberDto nearestHouseUpper = null;
		//for debug purpose, need to be removed
		StringBuffer sb = new StringBuffer();
		for (HouseNumberDto candidate :houseNumbersList){
			if (candidate!=null){
				sb.append(candidate.getNumber()).append(",");
			}
		}
		logger.info("will analyze HN  : "+sb.toString());
		
		for (HouseNumberDto candidate :houseNumbersList){
			if (candidate != null && candidate.getNumber()!=null){
				Integer candidateNormalized;
				if (countryCode!=null && ("SK".equalsIgnoreCase(countryCode) || "CZ".equalsIgnoreCase(countryCode))){
					candidateNormalized = HouseNumberUtil.normalizeSkCzNumberToInt(candidate.getNumber());
				} else {
					candidateNormalized = HouseNumberUtil.normalizeNumberToInt(candidate.getNumber());
				}
				logger.error("candidateNormalized='"+candidateNormalized+"' and houseNumberToFindAsInt='"+houseNumberToFindAsInt+"'");
				if (candidateNormalized!=null && houseNumberToFindAsInt != null &&  candidateNormalized.intValue() == houseNumberToFindAsInt.intValue()){
					logger.info("house number candidate found : "+candidate.getNumber());
					HouseNumberDtoInterpolation result = new HouseNumberDtoInterpolation(candidate.getLocation(),houseNumberToFindAsInt);
					result.setApproximative(false);
					return result;
				} else if (candidateNormalized < houseNumberToFindAsInt ){
					if (nearestLower ==null || candidateNormalized > nearestLower){
						nearestLower = candidateNormalized;
						nearestHouseLower = candidate;
					}
				} else if (candidateNormalized > houseNumberToFindAsInt){
					if (nearestUpper == null || candidateNormalized < nearestUpper){
						nearestUpper = candidateNormalized;
						nearestHouseUpper = candidate;
					}
				}
		}
		}
		logger.info("no exact house number candidate found for "+houseNumberToFindAsInt);
		//do interpolation
		if (nearestHouseLower == null && nearestHouseUpper ==null){
			logger.info(" no lower, nor upper house number found");
			return null;
		}
		HouseNumberDtoInterpolation result = new HouseNumberDtoInterpolation();
		result.setApproximative(true);
		if (nearestHouseUpper !=null){
			logger.info(" higher : "+nearestUpper);
			result.setHigherLocation(nearestHouseUpper.getLocation());
			result.setHigherNumber(nearestUpper);
		}
		if (nearestHouseLower != null){
			logger.info(" lower : "+nearestLower);
			result.setLowerLocation(nearestHouseLower.getLocation());
			result.setLowerNumber(nearestLower);
		}
			//this do interpolation, but if the street is not a line or is curve the point will be out
			if (doInterpolation){
				if (nearestHouseLower !=null && nearestHouseUpper != null){
					Point location = GeolocHelper.interpolatedPoint(nearestHouseLower.getLocation(), nearestHouseUpper.getLocation(), nearestUpper, nearestLower, houseNumberToFindAsInt);
					if (location !=null){
						result =new HouseNumberDtoInterpolation(location,houseNumberToFindAsInt);
						result.setApproximative(true);
						return result;
					} else {
						return null;
					}
				}
			}
		return result;
	}
	protected AddressResultsDto buildAddressResultDtoFromSolrResponseDtoCountry(List<SolrResponseDto> solResponseDtos){
		List<Address> addresses = new ArrayList<Address>();

		if (solResponseDtos != null && solResponseDtos.size() > 0) {
			if (logger.isDebugEnabled()) {
				logger.debug("found " + solResponseDtos.size() + " results");
			}
			SolrResponseDto response = solResponseDtos.get(0);
			Address address = new Address();
			address.setCountry(response.getName());
			address.setName(response.getName());
			address.setLat(response.getLat());
			address.setLng(response.getLng());
			address.setId(response.getFeature_id());
			address.setGeocodingLevel(GeocodingLevels.COUNTRY);
			addresses.add(address);
		}
		return new AddressResultsDto(addresses, 0L);
	}

	protected AddressResultsDto buildAddressResultDtoFromSolrResponseDto(List<SolrResponseDto> solResponseDtos, String houseNumberToFind) {
		List<Address> addresses = new ArrayList<Address>();

		if (solResponseDtos != null && solResponseDtos.size() > 0) {
			if (logger.isDebugEnabled()) {
				logger.debug("found " + solResponseDtos.size() + " results");
			}
			String lastName=null;
			String lastIsin=null;
			Point lastLocation=null;
			boolean sameStreet = false;
			boolean housenumberFound =false;
			int count=0;
			int numberOfStreetThatHaveTheSameName = 0;
			for (SolrResponseDto solrResponseDto : solResponseDtos) {
				count++;
				Address address = new Address();
				if (solrResponseDto == null) {
					continue;
				}
				address.setScore(solrResponseDto.getScore());
				if (!solrResponseDto.getPlacetype().equalsIgnoreCase(Street.class.getSimpleName())) {
					address.setName(solrResponseDto.getName());
				}
				
				if (solrResponseDto.getLat_admin_centre()!=null && solrResponseDto.getLng_admin_centre()!=null){
					address.setLat(solrResponseDto.getLat_admin_centre());
					address.setLng(solrResponseDto.getLng_admin_centre());
				} else {
					address.setLat(solrResponseDto.getLat());
					address.setLng(solrResponseDto.getLng());
				}
				if (solrResponseDto.getOpenstreetmap_id()!=null){
					address.setSourceId(solrResponseDto.getOpenstreetmap_id());
				} else if (solrResponseDto.getFeature_id()!=null){
					address.setSourceId(solrResponseDto.getFeature_id());
				}  
				address.setId(solrResponseDto.getFeature_id());
				String countryCode = solrResponseDto.getCountry_code();
				address.setCountryCode(countryCode);
				if (solrResponseDto.getPlacetype().equalsIgnoreCase(Adm.class.getSimpleName())) {
					address.setState(solrResponseDto.getName());
				}else if (solrResponseDto.getAdm1_name() != null) {
					address.setState(solrResponseDto.getAdm1_name());
				} else if (solrResponseDto.getAdm2_name() != null) {
					address.setState(solrResponseDto.getAdm2_name());
				} 
				address.setAdm1Name(solrResponseDto.getAdm1_name());
				address.setAdm2Name(solrResponseDto.getAdm2_name());
				address.setAdm3Name(solrResponseDto.getAdm3_name());
				address.setAdm4Name(solrResponseDto.getAdm4_name());
				address.setAdm5Name(solrResponseDto.getAdm5_name());
				if (solrResponseDto.getZipcodes() != null && solrResponseDto.getZipcodes().size() > 0) {
					address.setZipCode(labelGenerator.getBestZipString(solrResponseDto.getZipcodes()));
				} else if (solrResponseDto.getIs_in_zip()!=null && solrResponseDto.getIs_in_zip().size()>=1){
					address.setZipCode(labelGenerator.getBestZipString(solrResponseDto.getIs_in_zip()));
				}
				Integer houseNumberToFindAsInt;
				if (countryCode!=null && ("SK".equalsIgnoreCase(countryCode) || "CZ".equalsIgnoreCase(countryCode))){
					houseNumberToFindAsInt = HouseNumberUtil.normalizeSkCzNumberToInt(houseNumberToFind);
				} else {
					houseNumberToFindAsInt = HouseNumberUtil.normalizeNumberToInt(houseNumberToFind);
				}
				if (solrResponseDto.getPlacetype().equalsIgnoreCase(Street.class.getSimpleName())) {
					String streetName = solrResponseDto.getName();
					String isIn = solrResponseDto.getIs_in();
					Point curLoc = GeolocHelper.createPoint(solrResponseDto.getLng(),solrResponseDto.getLat());
					if (solrResponseDto.getIs_in_place()!=null){
						 isIn = isIn +" "+ solrResponseDto.getIs_in_place();
					}
					if (!isEmptyString(streetName)){ 
						if(streetName.equalsIgnoreCase(lastName) && isIn!=null && isIn.equalsIgnoreCase(lastIsin) && lastLocation!=null && !(GeolocHelper.distance(lastLocation, curLoc)>12000)){
							logger.debug("same street");
							sameStreet=true;//probably the same street
							if (housenumberFound){
								continue;
								//do nothing it has already been found in the street
							}else {
								numberOfStreetThatHaveTheSameName++;
							address.setStreetName(solrResponseDto.getName());
							address.setStreetRef(solrResponseDto.getStreet_ref());
							address.setCity(solrResponseDto.getIs_in());
							setStateInAddress(solrResponseDto, address);
							if (solrResponseDto.getIs_in_zip()!=null && solrResponseDto.getIs_in_zip().size()>=1){
								address.setZipCode(solrResponseDto.getIs_in_zip().iterator().next());
							}
							address.setDependentLocality(solrResponseDto.getIs_in_place());
							//now search for houseNumber
							List<HouseNumberDto> houseNumbersList = solrResponseDto.getHouse_numbers();
							//if(houseNumberToFind!=null && houseNumbersList!=null && houseNumbersList.size()>0){ //don't verify if it is null or not because if the first streets have no house number, we won't
							//count them as street that has same streetname
							boolean doInterpolation = false;
							if (isInterpolationPossible(solrResponseDto) ){
								doInterpolation=true;
							}
							HouseNumberDtoInterpolation houseNumber = searchHouseNumber(houseNumberToFindAsInt,houseNumbersList,countryCode, doInterpolation);
								if (houseNumber !=null){
									if (houseNumber.isApproximative() ){
										 if (houseNumber.getExactNumerAsString()!=null){
											 logger.debug("found approximative "+houseNumber.getExactNumerAsString());
										 } else {
												logger.debug("found nothing");
										 }
										
									} else if (houseNumber.getExactLocation()!=null){
										logger.debug("found exact "+houseNumber.getExactNumerAsString());
										housenumberFound=true;
										address.setHouseNumber(houseNumber.getExactNumerAsString());
										address.setLat(houseNumber.getExactLocation().getY());
										address.setLng(houseNumber.getExactLocation().getX());
										//remove the last results added
										for (numberOfStreetThatHaveTheSameName--;numberOfStreetThatHaveTheSameName>=0;numberOfStreetThatHaveTheSameName--){
											addresses.remove(addresses.size()-1-numberOfStreetThatHaveTheSameName);
										}
									}
								} else{
									housenumberFound=false;
								}
							//}
						}
						} else { //the streetName is different,
							logger.debug("not same street");
							sameStreet=false;
							//remove the last results added
							for (numberOfStreetThatHaveTheSameName--;numberOfStreetThatHaveTheSameName>=0;numberOfStreetThatHaveTheSameName--){
								addresses.remove(addresses.size()-1-numberOfStreetThatHaveTheSameName);
							}
							numberOfStreetThatHaveTheSameName=0;
							//populate fields
							address.setStreetName(solrResponseDto.getName());
							address.setCity(solrResponseDto.getIs_in());
							setStateInAddress(solrResponseDto, address);
							if (solrResponseDto.getIs_in_zip()!=null && solrResponseDto.getIs_in_zip().size()>=1){
								address.setZipCode(solrResponseDto.getIs_in_zip().iterator().next());
							}
							address.setDependentLocality(solrResponseDto.getIs_in_place());
							//search for housenumber
							List<HouseNumberDto> houseNumbersList = solrResponseDto.getHouse_numbers();
							if(houseNumberToFind!=null && houseNumbersList!=null && houseNumbersList.size()>0){
								boolean doInterpolation = false;
								if (isInterpolationPossible(solrResponseDto) ){
									doInterpolation=true;
								}
								HouseNumberDtoInterpolation houseNumber = searchHouseNumber(houseNumberToFindAsInt,houseNumbersList,countryCode, doInterpolation);
							if (houseNumber !=null){
								if (houseNumber.isApproximative()){
									
								} else {
									housenumberFound=true;
									address.setHouseNumber(houseNumber.getExactNumerAsString());
									address.setLat(houseNumber.getExactLocation().getY());
									address.setLng(houseNumber.getExactLocation().getX());
								}
							} else {
								housenumberFound=false;
							}
							}
						}
			  } else {//streetname is null, we search for housenumber anyway
					address.setCity(solrResponseDto.getIs_in());
					setStateInAddress(solrResponseDto, address);
					if (solrResponseDto.getIs_in_zip()!=null && solrResponseDto.getIs_in_zip().size()>=1){
						address.setZipCode(solrResponseDto.getIs_in_zip().iterator().next());
					}
					address.setDependentLocality(solrResponseDto.getIs_in_place());
				  List<HouseNumberDto> houseNumbersList = solrResponseDto.getHouse_numbers();
					if(houseNumberToFind!=null && houseNumbersList!=null && houseNumbersList.size()>0){
						boolean doInterpolation = false;
						if (isInterpolationPossible(solrResponseDto) ){
							doInterpolation=true;
						}
						HouseNumberDtoInterpolation houseNumber = searchHouseNumber(houseNumberToFindAsInt,houseNumbersList,countryCode, doInterpolation);
					if (houseNumber !=null){
						if (houseNumber.isApproximative()){
							
						} else {
							housenumberFound=true;
							address.setHouseNumber(houseNumber.getExactNumerAsString());
							address.setLat(houseNumber.getExactLocation().getY());
							address.setLng(houseNumber.getExactLocation().getX());
						}
					} else {
						housenumberFound=false;
					}
				}
			  }
					lastName=streetName;
					lastIsin = isIn;
					lastLocation=curLoc;
				} else if (solrResponseDto.getPlacetype().equalsIgnoreCase(City.class.getSimpleName())){
					address.setCity(solrResponseDto.getName());
					//populateAddressFromCity(solrResponseDto, address);
				} else if (solrResponseDto.getPlacetype().equalsIgnoreCase(CitySubdivision.class.getSimpleName())) {
					address.setQuarter(solrResponseDto.getName());
				}
				 
				if (logger.isDebugEnabled() && solrResponseDto != null) {
					logger.debug("=>place (" + (solrResponseDto.getOpenstreetmap_id()==null?solrResponseDto.getFeature_id():solrResponseDto.getOpenstreetmap_id())+") : "+solrResponseDto.getName() +" in "+solrResponseDto.getIs_in());
				}
				address.getGeocodingLevel();//force calculation of geocodingLevel
				if ((solrResponseDto.getPlacetype().equalsIgnoreCase(Street.class.getSimpleName()) && address.getHouseNumber()!=null) || solrResponseDto.getFully_qualified_name()==null){
					//we need to update the labels
					address.setFormatedFull(labelGenerator.getFullyQualifiedName(address));
				} else {
					address.setFormatedFull(solrResponseDto.getFully_qualified_name());
				}
				address.setFormatedPostal(addressFormater.getEnvelopeAddress(address, DisplayMode.COMMA));
				//set the street type after postal because street type is something like RESIDENTIAL and 
				//has not the same meaning than with address parsing
				address.setStreetType(solrResponseDto.getStreet_type());
				//if (!sameStreet){
					addresses.add(address);
				//}
					if (sameStreet && solResponseDtos.size()==count){
						//System.out.println("need remove");
						//remove the last results added
						for (numberOfStreetThatHaveTheSameName--;numberOfStreetThatHaveTheSameName>=0;numberOfStreetThatHaveTheSameName--){
							addresses.remove(addresses.size()-1-numberOfStreetThatHaveTheSameName);
						}
					}
				sameStreet=false;

			}
		}
		return new AddressResultsDto(addresses, 0L);
	}

	protected void setStateInAddress(SolrResponseDto solrResponseDto,
			Address address) {
		if (solrResponseDto.getCountry_code()!=null && solrResponseDto.getCountry_code().equalsIgnoreCase("FR") && solrResponseDto.getAdm2_name()!=null ){ //avoid france metropolitaine in state
			address.setState(solrResponseDto.getAdm2_name());
		} else if (solrResponseDto.getIs_in_adm()!=null) {
			address.setState(solrResponseDto.getIs_in_adm());
		}
	}

	protected boolean isInterpolationPossible(SolrResponseDto solrResponseDto) {
		return 
				solrResponseDto.getAzimuth_start()!=null && 
				solrResponseDto.getAzimuth_end()!=null &&
				Math.abs(solrResponseDto.getAzimuth_start()-solrResponseDto.getAzimuth_end()) < INTERPOLATION_CURVE_TOLERANCE;
	}
	


	private boolean isIntersection(Address address) {
		return address.getStreetNameIntersection() != null;
	}

	

	protected List<SolrResponseDto> findStreetInText(String text, String countryCode, Point point, boolean fuzzy, Double radius) {
		List<SolrResponseDto> streets = findInText(text, countryCode, point, com.gisgraphy.fulltext.Constants.STREET_PLACETYPE, fuzzy, radius);
		//now that we use bounding box it is to necessary to sort by distance 
		/*Point location;
		if (point != null) {
			for (SolrResponseDto solrResponseDto : streets) {
				Double longitude = solrResponseDto.getLng();
				Double latitude = solrResponseDto.getLat();
				if (latitude != null && longitude != null) {
					location = GeolocHelper.createPoint(longitude.floatValue(), latitude.floatValue());
					Double distance = GeolocHelper.distance(location, point);
					solrResponseDto.setDistance(distance);
				}
			}
			Collections.sort(streets, comparator);
		}*/
		return streets;
	}

	protected List<SolrResponseDto> findInText(String text, String countryCode, Point point, Class<?>[] placetypes,boolean fuzzy, Double radius) {
		if (isEmptyString(text)) {
			return new ArrayList<SolrResponseDto>();
		}
		FulltextQuery query = new FulltextQuery(text, Pagination.paginate().from(0).to(FulltextQuerySolrHelper.NUMBER_OF_STREET_TO_RETRIEVE), DEFAULT_OUTPUT, placetypes, countryCode);
		query.withAllWordsRequired(false).withoutSpellChecking().withFuzzy(fuzzy);
		if (fuzzy){
			query.withFuzzy(fuzzy);
		}
		if (point != null) {
			query.around(point);
			if (radius!=null){
				query.withRadius(radius);
			}
		}
		FulltextResultsDto results = fullTextSearchEngine.executeQuery(query);
		if (results.getResultsSize() >= 1) {
			return results.getResults();
		} else {
			return new ArrayList<SolrResponseDto>();
		}
	}

	


	protected List<SolrResponseDto> findExactMatches(String text, String countryCode,boolean fuzzy, Point point, Double radius, Class[] placetypes) {
		if (isEmptyString(text)) {
			return new ArrayList<SolrResponseDto>();
		}
		if (placetypes==null){
			placetypes = com.gisgraphy.fulltext.Constants.CITY_CITYSUB_ADM_PLACETYPE;
		}
		FulltextQuery query = new FulltextQuery(text, TEN_RESULT_PAGINATION, DEFAULT_OUTPUT,placetypes , countryCode);
		query.withAllWordsRequired(true).withoutSpellChecking().withFuzzy(fuzzy);
		if (point!=null){
			query.around(point);
			query.withRadius(radius);
		}
		FulltextResultsDto results = fullTextSearchEngine.executeQuery(query);
		if (results.getResultsSize() >= 1) {
			return results.getResults();
		} else {
			return new ArrayList<SolrResponseDto>();
		}
	}

	

	@Autowired
	public void setAddressParser(IAddressParserService addressParser) {
		this.addressParser = addressParser;
	}

	@Autowired
	public void setFullTextSearchEngine(FullTextSearchEngine fullTextSearchEngine) {
		this.fullTextSearchEngine = fullTextSearchEngine;
	}

	@Autowired
	public void setStatsUsageService(IStatsUsageService statsUsageService) {
		this.statsUsageService = statsUsageService;
	}

	@Autowired
	public void setImporterConfig(ImporterConfig importerConfig) {
		this.importerConfig = importerConfig;
	}

	@Autowired
	public void setGisgraphyConfig(GisgraphyConfig gisgraphyConfig) {
		this.gisgraphyConfig = gisgraphyConfig;
	}

}
