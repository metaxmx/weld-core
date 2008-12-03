/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.webbeans.bean.proxy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import javax.webbeans.DefinitionException;
import javax.webbeans.UnproxyableDependencyException;
import javax.webbeans.manager.Bean;

import org.jboss.webbeans.ManagerImpl;
import org.jboss.webbeans.util.Strings;

import com.google.common.collect.ForwardingMap;

/**
 * A proxy pool for holding scope adaptors (client proxies)
 * 
 * @author Nicklas Karlsson
 * 
 * @see org.jboss.webbeans.bean.proxy.ProxyMethodHandler
 */
public class ProxyPool
{
   /**
    * A container/cache for previously created proxies
    * 
    * @author Nicklas Karlsson
    */
   private class Pool extends ForwardingMap<Bean<?>, Object>
   {

      Map<Bean<?>, Object> delegate;

      public Pool()
      {
         delegate = new ConcurrentHashMap<Bean<?>, Object>();
      }

      @SuppressWarnings("unchecked")
      public <T> T get(Bean<T> key)
      {
         return (T) super.get(key);
      }

      @Override
      protected Map<Bean<?>, Object> delegate()
      {
         return delegate;
      }

      @Override
      public String toString()
      {
         return Strings.mapToString("ProxyPool (bean -> proxy): ", delegate);
      }

   }

   private ManagerImpl manager;
   private Pool pool;

   public ProxyPool(ManagerImpl manager)
   {
      this.manager = manager;
      this.pool = new Pool();
   }

   /**
    * Type info (interfaces and superclasses) for a class
    * 
    * @author Nicklas Karlsson
    */
   private class TypeInfo
   {
      Class<?>[] interfaces;
      Class<?> superclass;
   }

   /**
    * Gets the type info for a class
    * 
    * Looks through the give methods and organizes it into a TypeInfo object
    * containing an array of interfaces and the most common superclass. Adds
    * Serializable to the interfaces list also.
    * 
    * @param types A set of types (interfaces and superclasses) of a class
    * @return The TypeInfo with categorized information
    */
   private TypeInfo getTypeInfo(Set<Class<?>> types)
   {
      TypeInfo typeInfo = new TypeInfo();
      List<Class<?>> interfaces = new ArrayList<Class<?>>();
      Class<?> superclass = null;
      for (Class<?> type : types)
      {
         if (type.isInterface())
         {
            interfaces.add(type);
         }
         else if (superclass == null || (type != Object.class && superclass.isAssignableFrom(type)))
         {
            superclass = type;
         }
      }
      interfaces.add(Serializable.class);
      typeInfo.interfaces = interfaces.toArray(new Class<?>[0]);
      typeInfo.superclass = superclass;
      return typeInfo;
   }

   /**
    * Creates a Javassist scope adaptor (client proxy) for a bean
    * 
    * Creates a Javassist proxy factory. Gets the type info. Sets the interfaces
    * and superclass to the factory. Hooks in the MethodHandler and creates the
    * proxy.
    * 
    * @param bean The bean to proxy
    * @param beanIndex The index to the bean in the manager bean list
    * @return A Javassist proxy
    * @throws InstantiationException When the proxy couldn't be created
    * @throws IllegalAccessException When the proxy couldn't be created
    */
   @SuppressWarnings("unchecked")
   private <T> T createClientProxy(Bean<T> bean, int beanIndex) throws InstantiationException, IllegalAccessException
   {
      ProxyFactory proxyFactory = new ProxyFactory();
      TypeInfo typeInfo = getTypeInfo(bean.getTypes());
      proxyFactory.setInterfaces(typeInfo.interfaces);
      proxyFactory.setSuperclass(typeInfo.superclass);
      T clientProxy = (T) proxyFactory.createClass().newInstance();
      ProxyMethodHandler proxyMethodHandler = new ProxyMethodHandler(bean, beanIndex, manager);
      ((ProxyObject) clientProxy).setHandler(proxyMethodHandler);
      return clientProxy;
   }

   /**
    * Gets a client proxy for a bean
    * 
    * Looks for a proxy in the pool. If not found, one is created and added to
    * the pool
    * 
    * @param bean
    * @return
    */
   public Object getClientProxy(Bean<?> bean)
   {
      Object clientProxy = pool.get(bean);
      if (clientProxy == null)
      {
         try
         {
            int beanIndex = manager.getBeans().indexOf(bean);
            if (beanIndex < 0)
            {
               throw new DefinitionException(bean + " is not known to the manager");
            }
            clientProxy = createClientProxy(bean, beanIndex);
         }
         catch (DefinitionException e)
         {
            throw e;
         }
         catch (Exception e)
         {
            // TODO: What to *really* do here?
            throw new UnproxyableDependencyException("Could not create client proxy for " + bean.getName(), e);
         }
         pool.put(bean, clientProxy);
      }
      return clientProxy;
   }

   @Override
   public String toString()
   {
      StringBuffer buffer = new StringBuffer();
      buffer.append("Proxy pool\n");
      buffer.append(pool.toString() + "\n");
      return buffer.toString();
   }

}
