/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.balancer.properties;


import com.linkedin.d2.balancer.util.JacksonUtil;
import com.linkedin.d2.discovery.PropertyBuilder;
import com.linkedin.d2.discovery.PropertySerializationException;
import com.linkedin.d2.discovery.PropertySerializer;
import com.linkedin.r2.util.ConfigValueExtractor;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServicePropertiesJsonSerializer implements
    PropertySerializer<ServiceProperties>, PropertyBuilder<ServiceProperties>
{
  private static final Logger LOG = LoggerFactory.getLogger(ServicePropertiesJsonSerializer.class);
  private static final String LIST_SEPARATOR = ",";

  /**
   * Map from service name => Map of properties for that service. This map is supplied by the client and will
   * override any server supplied config values. The inner map is a flat map (property name => property value) which
   * can include transport client properties, degrader properties etc. Our namespacing rules for property names
   * (e.g. http.loadBalancer.hashMethod, degrader.maxDropRate) allow the inner map to be flat.
   */

  private final Map<String, Map<String, Object>> _clientServicesConfig;


  public ServicePropertiesJsonSerializer()
  {
    this(Collections.emptyMap());
  }

  public ServicePropertiesJsonSerializer(Map<String, Map<String, Object>> clientServicesConfig)
  {
    _clientServicesConfig = validateClientServicesConfig(clientServicesConfig);
  }

  /**
   * Validates the keys in the inner map for the client supplied per service config.
   */
  private Map<String, Map<String, Object>> validateClientServicesConfig(Map<String, Map<String, Object>> clientServicesConfig)
  {
    Map<String, Map<String, Object>> validatedClientServicesConfig = new HashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : clientServicesConfig.entrySet())
    {
      String serviceName = entry.getKey();
      Map<String, Object> clientConfigForSingleService = entry.getValue();
      Map<String, Object> validatedClientConfigForSingleService = new HashMap<>();
      for (Map.Entry<String, Object> innerMapEntry : clientConfigForSingleService.entrySet())
      {
        String clientSuppliedConfigKey = innerMapEntry.getKey();
        Object clientSuppliedConfigValue = innerMapEntry.getValue();
        if (AllowedClientPropertyKeys.isAllowedConfigKey(clientSuppliedConfigKey))
        {
          validatedClientConfigForSingleService.put(clientSuppliedConfigKey, clientSuppliedConfigValue);
          LOG.info("Client supplied config key {} for service {}", new Object[]{clientSuppliedConfigKey, serviceName});
        }
      }
      if (!validatedClientConfigForSingleService.isEmpty())
      {
        validatedClientServicesConfig.put(serviceName, validatedClientConfigForSingleService);
      }
    }
    return validatedClientServicesConfig;
  }


  private Map<String, Object> getTransportClientPropertiesWithClientOverrides(String serviceName, Map<String, Object> transportClientProperties)
  {
    Object allowedClientOverrideKeysObj = transportClientProperties.get(PropertyKeys.ALLOWED_CLIENT_OVERRIDE_KEYS);
    Set<String> allowedClientOverrideKeys = new HashSet<>(ConfigValueExtractor.buildList(allowedClientOverrideKeysObj, LIST_SEPARATOR));

    Map<String, Object> clientSuppliedServiceProperties = _clientServicesConfig.get(serviceName);
    if (clientSuppliedServiceProperties != null)
    {
      LOG.debug("Client supplied configs for service {}", new Object[]{serviceName});

      // check for overrides
      for (String clientSuppliedKey : clientSuppliedServiceProperties.keySet())
      {
        // clients can only override config properties which have been allowed by the service
        if (allowedClientOverrideKeys.contains(clientSuppliedKey))
        {
          if (ClientServiceConfigValidator.isValidValue(transportClientProperties,
            clientSuppliedServiceProperties,
            clientSuppliedKey))
          {
            transportClientProperties.put(clientSuppliedKey, clientSuppliedServiceProperties.get(clientSuppliedKey));
            LOG.info("Client overrode config property {} for service {}. This is being used to instantiate the Transport Client",
              new Object[]{clientSuppliedKey, serviceName});
          }
          else
          {
            LOG.warn("Client supplied config property {} with an invalid value {} for service {}",
              new Object[]{clientSuppliedKey,
                clientSuppliedServiceProperties.get(clientSuppliedKey),
                serviceName});
          }
        }
        else
        {
          LOG.warn("Client failed to override config property {} that is disallowed by service {}. Continuing without override.",
              clientSuppliedKey, serviceName);
        }
      }
    }
    return transportClientProperties;
  }


  public static void main(String[] args) throws UnsupportedEncodingException,
      URISyntaxException, PropertySerializationException
  {
    String serviceName = "testService";
    String clusterName = "testCluster";
    String path = "/foo/bar";
    List<String> loadBalancerStrategyList = Arrays.asList("degrader");

    ServiceProperties property =
      new ServiceProperties(serviceName, clusterName, path, loadBalancerStrategyList);

    ServicePropertiesJsonSerializer serializer = new ServicePropertiesJsonSerializer();
    System.err.println(new String(serializer.toBytes(property), "UTF-8"));
    System.err.println(serializer.fromBytes(serializer.toBytes(property)));
  }

  @Override
  public byte[] toBytes(ServiceProperties property)
  {
    try
    {
      return JacksonUtil.getObjectMapper().writeValueAsString(property).getBytes("UTF-8");
    }
    catch (Exception e)
    {
      LOG.error("Failed to serialize ServiceProperties: " + property, e);
    }

    return null;
  }

  @Override
  public ServiceProperties fromBytes(byte[] bytes) throws PropertySerializationException
  {
    try
    {
      @SuppressWarnings("unchecked")
      Map<String, Object> untyped =
          JacksonUtil.getObjectMapper().readValue(new String(bytes, "UTF-8"), Map.class);

      return fromMap(untyped);

    }
    catch (Exception e)
    {
      throw new PropertySerializationException(e);
    }
  }

  // Need to work around a compiler bug that doesn't obey the SuppressWarnings("unchecked")
  @SuppressWarnings("unchecked")
  private static <T> T mapGet(Map<String, Object> map, String key)
  {
    return (T) map.get(key);
  }

  public ServiceProperties fromMap(Map<String,Object> map)
  {
    Map<String,Object> loadBalancerStrategyProperties = mapGet(map,PropertyKeys.LB_STRATEGY_PROPERTIES);
    if (loadBalancerStrategyProperties == null)
    {
      loadBalancerStrategyProperties = Collections.emptyMap();
    }
    List<String> loadBalancerStrategyList = mapGet(map, PropertyKeys.LB_STRATEGY_LIST);
    if (loadBalancerStrategyList == null)
    {
      loadBalancerStrategyList = Collections.emptyList();
    }
    Map<String, Object> transportClientProperties = mapGet(map, PropertyKeys.TRANSPORT_CLIENT_PROPERTIES);
    if (transportClientProperties == null)
    {
      transportClientProperties = Collections.emptyMap();
    }
    Map<String, String> degraderProperties = mapGet(map, PropertyKeys.DEGRADER_PROPERTIES);
    if (degraderProperties == null)
    {
      degraderProperties = Collections.emptyMap();
    }
    List<URI> bannedList = mapGet(map, PropertyKeys.BANNED_URIS);
    if (bannedList == null)
    {
      bannedList = Collections.emptyList();
    }
    Set<URI> banned = new HashSet<URI>(bannedList);
    List<String> prioritizedSchemes = mapGet(map,PropertyKeys.PRIORITIZED_SCHEMES);

    Map<String, Object> metadataProperties = new HashMap<String,Object>();
    String isDefaultService = mapGet(map, PropertyKeys.IS_DEFAULT_SERVICE);
    if (isDefaultService != null && "true".equalsIgnoreCase(isDefaultService))
    {
      metadataProperties.put(PropertyKeys.IS_DEFAULT_SERVICE, isDefaultService);
    }
    String defaultRoutingToMaster = mapGet(map, PropertyKeys.DEFAULT_ROUTING_TO_MASTER);
    if (Boolean.valueOf(defaultRoutingToMaster))
    {
      metadataProperties.put(PropertyKeys.DEFAULT_ROUTING_TO_MASTER, defaultRoutingToMaster);
    }

    Map<String, Object> publishedMetadataProperties = mapGet(map, PropertyKeys.SERVICE_METADATA_PROPERTIES);
    if (publishedMetadataProperties != null)
    {
      metadataProperties.putAll(publishedMetadataProperties);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> backupRequests = (List<Map<String, Object>>) map.get(PropertyKeys.BACKUP_REQUESTS);
    if (backupRequests == null)
    {
      backupRequests = Collections.emptyList();
    }

    return new ServiceProperties((String) map.get(PropertyKeys.SERVICE_NAME),
                                 (String) map.get(PropertyKeys.CLUSTER_NAME),
                                 (String) map.get(PropertyKeys.PATH),
                                 loadBalancerStrategyList,
                                 loadBalancerStrategyProperties,
                                 getTransportClientPropertiesWithClientOverrides((String) map.get(PropertyKeys.SERVICE_NAME), transportClientProperties),
                                 degraderProperties,
                                 prioritizedSchemes,
                                 banned,
                                 metadataProperties,
                                 backupRequests);

  }
}
