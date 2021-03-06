/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth.plugin;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.security.AuthExpirationException;
import org.neo4j.kernel.api.security.exception.InvalidAuthTokenException;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.internal.Version;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.server.security.enterprise.auth.PredefinedRolesBuilder;
import org.neo4j.server.security.enterprise.auth.SecureHasher;
import org.neo4j.server.security.enterprise.auth.ShiroAuthToken;
import org.neo4j.server.security.enterprise.auth.plugin.api.AuthToken;
import org.neo4j.server.security.enterprise.auth.plugin.api.RealmOperations;
import org.neo4j.server.security.enterprise.auth.plugin.spi.AuthInfo;
import org.neo4j.server.security.enterprise.auth.plugin.spi.AuthPlugin;
import org.neo4j.server.security.enterprise.auth.plugin.spi.AuthenticationPlugin;
import org.neo4j.server.security.enterprise.auth.plugin.spi.AuthorizationPlugin;
import org.neo4j.server.security.enterprise.auth.plugin.spi.CustomCacheableAuthenticationInfo;
import org.neo4j.server.security.enterprise.auth.plugin.spi.RealmLifecycle;

import static org.neo4j.server.security.enterprise.auth.SecuritySettings.PLUGIN_REALM_NAME_PREFIX;

public class PluginRealm extends AuthorizingRealm implements RealmLifecycle
{
    private AuthenticationPlugin authenticationPlugin;
    private AuthorizationPlugin authorizationPlugin;
    private final Config config;
    private AuthPlugin authPlugin;
    private final Log log;
    private final Clock clock;
    private final SecureHasher secureHasher;

    private RealmOperations realmOperations = new PluginRealmOperations();

    public PluginRealm( Config config, LogProvider logProvider, Clock clock, SecureHasher secureHasher )
    {
        this.config = config;
        this.clock = clock;
        this.secureHasher = secureHasher;
        this.log = logProvider.getLog( getClass() );

        setCredentialsMatcher( new CredentialsMatcher() );

        // Synchronize this default value with the javadoc for RealmOperations.setAuthenticationCachingEnabled
        setAuthenticationCachingEnabled( false );

        // Synchronize this default value with the javadoc for RealmOperations.setAuthorizationCachingEnabled
        setAuthorizationCachingEnabled( true );

        setRolePermissionResolver( PredefinedRolesBuilder.rolePermissionResolver );
    }

    public PluginRealm( AuthenticationPlugin authenticationPlugin, AuthorizationPlugin authorizationPlugin,
            Config config, LogProvider logProvider, Clock clock, SecureHasher secureHasher )
    {
        this( config, logProvider, clock, secureHasher );
        this.authenticationPlugin = authenticationPlugin;
        this.authorizationPlugin = authorizationPlugin;
        resolvePluginName();
    }

    public PluginRealm( AuthPlugin authPlugin, Config config, LogProvider logProvider, Clock clock,
            SecureHasher secureHasher )
    {
        this( config, logProvider, clock, secureHasher );
        this.authPlugin = authPlugin;
        resolvePluginName();
    }

    private void resolvePluginName()
    {
        String pluginName = null;
        if ( authPlugin != null )
        {
            pluginName = authPlugin.name();
        }
        else if ( authenticationPlugin != null )
        {
            pluginName = authenticationPlugin.name();
        }
        else if ( authorizationPlugin != null )
        {
            pluginName = authorizationPlugin.name();
        }

        if ( pluginName != null && !pluginName.isEmpty() )
        {
            setName( PLUGIN_REALM_NAME_PREFIX + pluginName );
        }
        // Otherwise we rely on the Shiro default generated name
    }

    private Collection<AuthorizationPlugin.PrincipalAndRealm> getPrincipalAndRealmCollection(
            PrincipalCollection principalCollection
    )
    {
        Collection<AuthorizationPlugin.PrincipalAndRealm> principalAndRealmCollection = new ArrayList<>();

        for ( String realm : principalCollection.getRealmNames() )
        {
            for ( Object principal : principalCollection.fromRealm( realm ) )
            {
                principalAndRealmCollection.add( new AuthorizationPlugin.PrincipalAndRealm( principal, realm ) );
            }
        }

        return principalAndRealmCollection;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo( PrincipalCollection principals )
    {
        if ( authorizationPlugin != null )
        {
            org.neo4j.server.security.enterprise.auth.plugin.spi.AuthorizationInfo authorizationInfo =
                    authorizationPlugin.authorize( getPrincipalAndRealmCollection( principals ) );
            if ( authorizationInfo != null )
            {
                return PluginAuthorizationInfo.create( authorizationInfo );
            }
        }
        else if ( authPlugin != null && !principals.fromRealm( getName() ).isEmpty() )
        {
            // The cached authorization info has expired.
            // Since we do not have the subject's credentials we cannot perform a new
            // authenticateAndAuthorize() to renew authorization info.
            // Instead we need to fail with a special status, so that the client can react by re-authenticating.
            throw new AuthExpirationException( "Plugin '" + getName() + "' authorization info expired." );
        }
        return null;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo( AuthenticationToken token ) throws AuthenticationException
    {
        if ( token instanceof ShiroAuthToken )
        {
            try
            {
                AuthToken pluginAuthToken =
                        PluginApiAuthToken.createFromMap( ((ShiroAuthToken) token).getAuthTokenMap() );
                if ( authPlugin != null )
                {
                    AuthInfo authInfo = authPlugin.authenticateAndAuthorize( pluginAuthToken );
                    if ( authInfo != null )
                    {
                        PluginAuthInfo pluginAuthInfo =
                                PluginAuthInfo.createCacheable( authInfo, getName(), secureHasher );

                        cacheAuthorizationInfo( pluginAuthInfo );

                        return pluginAuthInfo;
                    }
                }
                else if ( authenticationPlugin != null )
                {
                    org.neo4j.server.security.enterprise.auth.plugin.spi.AuthenticationInfo authenticationInfo =
                            authenticationPlugin.authenticate( pluginAuthToken );
                    if ( authenticationInfo != null )
                    {
                        return PluginAuthenticationInfo.createCacheable( authenticationInfo, getName(), secureHasher );
                    }
                }
            }
            catch ( org.neo4j.server.security.enterprise.auth.plugin.api.AuthenticationException |
                    InvalidAuthTokenException e )
            {
                throw new AuthenticationException( e.getMessage(), e.getCause() );
            }
        }
        return null;
    }

    private void cacheAuthorizationInfo( PluginAuthInfo authInfo )
    {
        // Use the existing authorizationCache in our base class
        Cache<Object, AuthorizationInfo> authorizationCache = getAuthorizationCache();
        Object key = getAuthorizationCacheKey( authInfo.getPrincipals() );
        authorizationCache.put( key, authInfo );
    }

    @Override
    protected Object getAuthorizationCacheKey( PrincipalCollection principals )
    {
        return getAvailablePrincipal( principals );
    }

    @Override
    protected Object getAuthenticationCacheKey( AuthenticationToken token )
    {
        return token != null ? token.getPrincipal() : null;
    }

    @Override
    public boolean supports( AuthenticationToken token )
    {
        return supportsSchemeAndRealm( token );
    }

    private boolean supportsSchemeAndRealm( AuthenticationToken token )
    {
        if ( token instanceof ShiroAuthToken )
        {
            ShiroAuthToken shiroAuthToken = (ShiroAuthToken) token;
            return shiroAuthToken.supportsRealm( getName() );
        }
        return false;
    }

    @Override
    public void initialize( RealmOperations ignore ) throws Throwable
    {
        if ( authenticationPlugin != null )
        {
            authenticationPlugin.initialize( this.realmOperations );
        }
        if ( authorizationPlugin != null && authorizationPlugin != authenticationPlugin )
        {
            authorizationPlugin.initialize( this.realmOperations );
        }
        if ( authPlugin != null )
        {
            authPlugin.initialize( this.realmOperations );
        }
    }

    @Override
    public void start() throws Throwable
    {
        if ( authenticationPlugin != null )
        {
            authenticationPlugin.start();
        }
        if ( authorizationPlugin != null && authorizationPlugin != authenticationPlugin )
        {
            authorizationPlugin.start();
        }
        if ( authPlugin != null )
        {
            authPlugin.start();
        }
    }

    @Override
    public void stop() throws Throwable
    {
        if ( authenticationPlugin != null )
        {
            authenticationPlugin.stop();
        }
        if ( authorizationPlugin != null && authorizationPlugin != authenticationPlugin )
        {
            authorizationPlugin.stop();
        }
        if ( authPlugin != null )
        {
            authPlugin.stop();
        }
    }

    @Override
    public void shutdown() throws Throwable
    {
        if ( authenticationPlugin != null )
        {
            authenticationPlugin.shutdown();
        }
        if ( authorizationPlugin != null && authorizationPlugin != authenticationPlugin )
        {
            authorizationPlugin.shutdown();
        }
        if ( authPlugin != null )
        {
            authPlugin.shutdown();
        }
    }

    private static CustomCacheableAuthenticationInfo.CredentialsMatcher getCustomCredentialsMatcherIfPresent(
            AuthenticationInfo info
    )
    {
        if ( info instanceof CustomCredentialsMatcherSupplier )
        {
            return ((CustomCredentialsMatcherSupplier) info).getCredentialsMatcher();
        }
        return null;
    }

    private class CredentialsMatcher implements org.apache.shiro.authc.credential.CredentialsMatcher
    {
        @Override
        public boolean doCredentialsMatch( AuthenticationToken token, AuthenticationInfo info )
        {
            CustomCacheableAuthenticationInfo.CredentialsMatcher
                    customCredentialsMatcher = getCustomCredentialsMatcherIfPresent( info );

            if ( customCredentialsMatcher != null )
            {
                // Authentication info is originating from a CustomCacheableAuthenticationInfo
                Map<String,Object> authToken = ((ShiroAuthToken) token).getAuthTokenMap();
                try
                {
                    AuthToken pluginApiAuthToken = PluginApiAuthToken.createFromMap( authToken );
                    return customCredentialsMatcher.doCredentialsMatch( pluginApiAuthToken );
                }
                catch ( InvalidAuthTokenException e )
                {
                    throw new AuthenticationException( e.getMessage() );
                }
            }
            else if ( info.getCredentials() != null )
            {
                // Authentication info is originating from a CacheableAuthenticationInfo or a CacheableAuthInfo
                return secureHasher.getHashedCredentialsMatcher()
                        .doCredentialsMatch( PluginShiroAuthToken.of( token ), info );
            }
            else
            {
                // Authentication info is originating from an AuthenticationInfo or an AuthInfo
                if ( PluginRealm.this.isAuthenticationCachingEnabled() )
                {
                    log.error( "Authentication caching is enabled in plugin %s but it does not return " +
                               "cacheable credentials. This configuration is not secure.", getName() );
                    return false;
                }
                return true; // Always match if we do not cache credentials
            }
        }
    }

    private class PluginRealmOperations implements RealmOperations
    {
        private Log innerLog = new Log()
        {
            private String withPluginName( String msg )
            {
                return "{" + getName() + "} " + msg;
            }

            @Override
            public void debug( String message )
            {
                log.debug( withPluginName( message ) );
            }

            @Override
            public void info( String message )
            {
                log.info( withPluginName( message ) );
            }

            @Override
            public void warn( String message )
            {
                log.warn( withPluginName( message ) );
            }

            @Override
            public void error( String message )
            {
                log.error( withPluginName( message ) );
            }

            @Override
            public boolean isDebugEnabled()
            {
                return log.isDebugEnabled();
            }
        };

        @Override
        public Path neo4jHome()
        {
            return config.get( GraphDatabaseSettings.neo4j_home ).getAbsoluteFile().toPath();
        }

        @Override
        public Optional<Path> neo4jConfigFile()
        {
            return config.getConfigFile();
        }

        @Override
        public String neo4jVersion()
        {
            return Version.getKernel().getReleaseVersion();
        }

        @Override
        public Clock clock()
        {
            return clock;
        }

        @Override
        public Log log()
        {
            return innerLog;
        }

        @Override
        public void setAuthenticationCachingEnabled( boolean authenticationCachingEnabled )
        {
            PluginRealm.this.setAuthenticationCachingEnabled( authenticationCachingEnabled );
        }

        @Override
        public void setAuthorizationCachingEnabled( boolean authorizationCachingEnabled )
        {
            PluginRealm.this.setAuthorizationCachingEnabled( authorizationCachingEnabled );
        }
    }
}
