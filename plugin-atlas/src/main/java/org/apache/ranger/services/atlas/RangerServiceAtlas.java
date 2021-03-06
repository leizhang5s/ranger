/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ranger.services.atlas;

import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.client.BaseClient;
import org.apache.ranger.plugin.client.HadoopException;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerService;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.apache.ranger.plugin.util.PasswordUtils;

import javax.security.auth.Subject;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;

public class RangerServiceAtlas extends RangerBaseService {
	private static final Log LOG = LogFactory.getLog(RangerServiceAtlas.class);

	public static final String RESOURCE_SERVICE               = "atlas-service";
	public static final String RESOURCE_TYPE_CATEGORY         = "type-category";
	public static final String RESOURCE_TYPE_NAME             = "type";
	public static final String RESOURCE_ENTITY_TYPE           = "entity-type";
	public static final String RESOURCE_ENTITY_CLASSIFICATION = "entity-classification";
	public static final String RESOURCE_ENTITY_ID             = "entity";
	public static final String CONFIG_REST_ADDRESS            = "atlas.rest.address";
	public static final String CONFIG_USERNAME                = "username";
	public static final String CONFIG_PASSWORD                = "password";

	private static final String TYPE_ENTITY         = "entity";
	private static final String TYPE_CLASSIFICATION = "classification";
	private static final String TYPE_STRUCT         = "struct";
	private static final String TYPE_ENUM           = "enum";
	private static final String TYPE_RELATIONSHIP   = "relationship";

	private static final String URL_LOGIN                = "/j_spring_security_check";
	private static final String URL_GET_TYPESDEF_HEADERS = "/api/atlas/v2/types/typedefs/headers";

	private static final String WEB_RESOURCE_CONTENT_TYPE = "application/x-www-form-urlencoded";
	private static final String CONNECTION_ERROR_MSG      =   " You can still save the repository and start creating"
	                                                        + " policies, but you would not be able to use autocomplete for"
	                                                        + " resource names. Check ranger_admin.log for more info.";

	public RangerServiceAtlas() {
		super();
	}

	@Override
	public void init(RangerServiceDef serviceDef, RangerService service) {
		super.init(serviceDef, service);
	}

	@Override
	public Map<String, Object> validateConfig() throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerServiceAtlas.validateConfig()");
		}

		AtlasServiceClient client = new AtlasServiceClient(getServiceName(), configs);

		Map<String, Object> ret = client.validateConfig();

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerServiceAtlas.validateConfig(): "+ ret );
		}

		return ret;
	}

	@Override
	public List<String> lookupResource(ResourceLookupContext context)throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerServiceAtlas.lookupResource("+ context + ")");
		}

		AtlasServiceClient client = new AtlasServiceClient(getServiceName(), configs);

		List<String> ret = client.lookupResource(context);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerServiceAtlas.lookupResource("+ context + "): " + ret);
		}

		return ret;
	}

    @Override
    public List<RangerPolicy> getDefaultRangerPolicies() throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RangerServiceAtlas.getDefaultRangerPolicies() ");
        }

        List<RangerPolicy> ret = super.getDefaultRangerPolicies();

        for (RangerPolicy defaultPolicy : ret) {
            for (RangerPolicy.RangerPolicyItem defaultPolicyItem : defaultPolicy.getPolicyItems()) {
                List<String> users = defaultPolicyItem.getUsers();

                String atlasAdminUser = service.getConfigs().get("atlas.admin.user");
                if (StringUtils.isBlank(atlasAdminUser)) {
                    atlasAdminUser = "admin";
                }

                users.add(atlasAdminUser);
                defaultPolicyItem.setUsers(users);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== RangerServiceAtlas.getDefaultRangerPolicies() ");
        }
        return ret;
    }

    private static class AtlasServiceClient extends BaseClient {
		private static final String[] TYPE_CATEGORIES = new String[] { "classification", "enum", "entity", "relationship", "struct" };

		Map<String, List<String>> typesDef = new HashMap<>();

		public AtlasServiceClient(String serviceName, Map<String, String> serviceConfig) {
			super(serviceName, serviceConfig);
		}

		public Map<String, Object> validateConfig() {
			Map<String, Object> ret = new HashMap<>();

			loginToAtlas(Client.create());

			BaseClient.generateResponseDataMap(true, "ConnectionTest Successful", "ConnectionTest Successful", null, null, ret);

			return ret;
		}

		public List<String> lookupResource(ResourceLookupContext lookupContext) {
			final List<String> ret           = new ArrayList<>();
			final String       userInput     = lookupContext.getUserInput();
			final List<String> currentValues = lookupContext.getResources().get(lookupContext.getResourceName());

			switch(lookupContext.getResourceName()) {
				case RESOURCE_TYPE_CATEGORY: {
					for (String typeCategory : TYPE_CATEGORIES) {
						addIfStartsWithAndNotExcluded(ret, typeCategory, userInput, currentValues);
					}
				}
				break;

				case RESOURCE_TYPE_NAME: {
					refreshTypesDefs();

					final List<String> typeCategories = lookupContext.getResources().get(RESOURCE_TYPE_CATEGORY);

					if (emptyOrContainsMatch(typeCategories, TYPE_CLASSIFICATION)) {
						addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_CLASSIFICATION), userInput, currentValues);
					}

					if (emptyOrContainsMatch(typeCategories, TYPE_ENTITY)) {
						addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_ENTITY), userInput, currentValues);
					}

					if (emptyOrContainsMatch(typeCategories, TYPE_ENUM)) {
						addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_ENUM), userInput, currentValues);
					}

					if (emptyOrContainsMatch(typeCategories, TYPE_STRUCT)) {
						addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_STRUCT), userInput, currentValues);
					}

					if (emptyOrContainsMatch(typeCategories, TYPE_RELATIONSHIP)) {
						addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_RELATIONSHIP), userInput, currentValues);
					}
				}
				break;

				case RESOURCE_ENTITY_TYPE: {
					refreshTypesDefs();

					addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_ENTITY), userInput, currentValues);
				}
				break;

				case RESOURCE_ENTITY_CLASSIFICATION: {
					refreshTypesDefs();

					addIfStartsWithAndNotExcluded(ret, typesDef.get(TYPE_CLASSIFICATION), userInput, currentValues);
				}
				break;

				default: {
					ret.add(lookupContext.getResourceName());
				}
			}

			return ret;
		}

		private ClientResponse loginToAtlas(Client client) {
			ClientResponse ret      = null;
			HadoopException excp     = null;
			String          loginUrl = null;

			for (String atlasUrl : getAtlasUrls()) {
				try {
					loginUrl = atlasUrl + URL_LOGIN;

					WebResource                    webResource = client.resource(loginUrl);
					MultivaluedMap<String, String> formData    = new MultivaluedMapImpl();
					String                         password    = null;

					try {
						password = PasswordUtils.decryptPassword(getPassword());
					} catch (Exception ex) {
						LOG.info("Password decryption failed; trying Atlas connection with received password string");
					}

					if (password == null) {
						password = getPassword();
					}

					formData.add("j_username", getUserName());
					formData.add("j_password", password);

					try {
						ret = webResource.type(WEB_RESOURCE_CONTENT_TYPE).post(ClientResponse.class, formData);
					} catch (Exception e) {
						LOG.error("failed to login to Atlas at " + loginUrl, e);
					}

					if (ret != null) {
						break;
					}
				} catch (Throwable t) {
					String msgDesc = "Exception while login to Atlas at : " + loginUrl;

					LOG.error(msgDesc, t);

					excp = new HadoopException(msgDesc, t);
					excp.generateResponseDataMap(false, BaseClient.getMessage(t), msgDesc + CONNECTION_ERROR_MSG, null, null);
				}
			}

			if (ret == null) {
				if (excp == null) {
					String msgDesc = "Exception while login to Atlas at : " + loginUrl;

					excp = new HadoopException(msgDesc);
					excp.generateResponseDataMap(false, "", msgDesc + CONNECTION_ERROR_MSG, null, null);
				}

				throw excp;
			}

			return ret;
		}

		private boolean refreshTypesDefs() {
			boolean ret = false;

			Subject subj = getLoginSubject();

			if (subj == null) {
				return ret;
			}

			Map<String, List<String>> typesDef = Subject.doAs(subj, new PrivilegedAction<Map<String, List<String>>>() {
				@Override
				public Map<String, List<String>> run() {
					Map<String, List<String>> ret  = null;

					for (String atlasUrl : getAtlasUrls()) {
						Client client = null;

						try {
							client = Client.create();

							ClientResponse      loginResponse = loginToAtlas(client);
							WebResource         webResource   = client.resource(atlasUrl + URL_GET_TYPESDEF_HEADERS);
							WebResource.Builder builder       = webResource.getRequestBuilder();

							for (NewCookie cook : loginResponse.getCookies()) {
								builder = builder.cookie(cook);
							}

							ClientResponse response = builder.get(ClientResponse.class);

							if (response != null) {
								String jsonString = response.getEntity(String.class);
								Gson   gson       = new Gson();
								List   types      = gson.fromJson(jsonString, List.class);

								ret = new HashMap<>();

								for (Object type : types) {
									if (type instanceof Map) {
										Map typeDef = (Map) type;

										Object name     = typeDef.get("name");
										Object category = typeDef.get("category");

										if (name != null && category != null) {
											String       strCategory  = category.toString().toLowerCase();
											List<String> categoryList = ret.get(strCategory);

											if (categoryList == null) {
												categoryList = new ArrayList<>();

												ret.put(strCategory, categoryList);
											}

											categoryList.add(name.toString());
										}
									}
								}

								break;
							}
						} catch (Throwable t) {
							String msgDesc = "Exception while getting Atlas Resource List.";
							LOG.error(msgDesc, t);
						} finally {
							if (client != null) {
								client.destroy();
							}
						}
					}

					return ret;
				}
			});

			if (typesDef != null) {
				this.typesDef = typesDef;
				ret = true;
			}

			return ret;
		}

		String[] getAtlasUrls() {
			String urlString = connectionProperties.get(CONFIG_REST_ADDRESS);

			String[] ret = urlString == null ? new String[0] : urlString.split(",");

			// remove separator at the end
			for (int i = 0; i < ret.length; i++) {
				String url = ret[i];

				while (url.length() > 0 && url.charAt(url.length() - 1) == '/') {
					url = url.substring(0, url.length() - 1);
				}

				ret[i] = url;
			}

			return ret;
		}

		String getUserName() {
			return connectionProperties.get(CONFIG_USERNAME);
		}

		String getPassword() {
			return connectionProperties.get(CONFIG_PASSWORD);
		}

		boolean emptyOrContainsMatch(List<String> list, String value) {
			if (list == null || list.isEmpty()) {
				return true;
			}

			for (String item : list) {
				if (StringUtils.equalsIgnoreCase(item, value) || FilenameUtils.wildcardMatch(value, item, IOCase.INSENSITIVE)) {
					return true;
				}
			}

			return false;
		}

		void addIfStartsWithAndNotExcluded(List<String> list, List<String> values, String prefix, List<String> excludeList) {
			if (values == null || list == null) {
				return;
			}

			for (String value : values) {
				addIfStartsWithAndNotExcluded(list, value, prefix, excludeList);
			}
		}

		void addIfStartsWithAndNotExcluded(List<String> list, String value, String prefix, List<String> excludeList) {
			if (value == null || list == null) {
				return;
			}

			if (prefix != null && !value.startsWith(prefix)) {
				return;
			}

			if (excludeList != null && excludeList.contains(value)) {
				return;
			}

			list.add(value);
		}
	}
}
