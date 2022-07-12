use crate::erase::{DynConnector, DynMiddleware};
use crate::http_connector::{
    ConnectorKey, HttpConnector, HttpConnectorError, MakeConnectorSettings,
};

use aws_smithy_async::rt::sleep::AsyncSleep;
use aws_smithy_http::result::SdkError;
use aws_smithy_types::retry::RetryConfig;
use aws_smithy_types::timeout::Config as TimeoutConfig;

use http::version::Version as HttpVersion;
use tokio::sync::RwLock;

use std::borrow::Cow;
use std::sync::Arc;

type SharedSmithyClient = std::sync::Arc<crate::Client>;
type ClientsMap =
    std::sync::Arc<RwLock<std::collections::HashMap<ConnectorKey<'static>, SharedSmithyClient>>>;

#[derive(Debug)]
pub struct SmithyClientPool {
    clients: ClientsMap,
    conf: SmithyClientPoolConf,
}

#[derive(Debug)]
pub struct SmithyClientPoolConf {
    sleep_impl: Option<Arc<dyn AsyncSleep>>,
    http_connector: Option<HttpConnector>,
    retry_config: Option<RetryConfig>,
    timeout_config: Option<TimeoutConfig>,
}

impl SmithyClientPool {
    pub fn new(conf: impl Into<SmithyClientPoolConf>) -> Self {
        let clients = std::sync::Arc::new(RwLock::new(std::collections::HashMap::new()));
        Self {
            clients,
            conf: conf.into(),
        }
    }

    pub async fn get_or_create_client<E>(
        &self,
        connector_key: &ConnectorKey,
    ) -> Result<SharedSmithyClient, SdkError<E>> {
        // Try to fetch an existing client and return early if we find one
        if let Some(client) = self.fetch_existing_client(&connector_key).await {
            return Ok(client);
        }

        // Otherwise, create the new client, store a copy of it in the client cache, and then return it
        match self
            .initialize_and_store_new_client(connector_key.into_owned())
            .await
        {
            Err(err) => {
                construction_failure = Some(err);
            }
            client => return client,
        }
    }

    async fn fetch_existing_client(
        &self,
        connector_key: &ConnectorKey<'_>,
    ) -> Option<SharedSmithyClient> {
        let clients = self.clients.read().await;
        clients.get(connector_key).cloned()
    }

    async fn initialize_and_store_new_client<M: Default, E>(
        &self,
        connector_key: ConnectorKey<'static>,
    ) -> Result<SharedSmithyClient, SdkError<E>> {
        let sleep_impl = self.conf.sleep_impl.clone();
        let connector = match &self.conf.http_connector {
            Some(connector) => Ok(connector.clone()),
            None => HttpConnector::try_default()
                .map_err(|err| SdkError::ConstructionFailure(err.into())),
        }?
        .load(&connector_key.make_connector_settings, sleep_impl.clone())
        .map_err(|err| SdkError::ConstructionFailure(err.into()))?;
        let mut builder = crate::Builder::new()
            .connector(connector)
            .middleware(M::default());
        let retry_config = self.conf.retry_config.as_ref().cloned().unwrap_or_default();
        let timeout_config = self
            .conf
            .timeout_config
            .as_ref()
            .cloned()
            .unwrap_or_default();
        builder.set_retry_config(retry_config.into());
        builder.set_timeout_config(timeout_config);
        // the builder maintains a try-state. To avoid suppressing the warning when sleep is unset,
        // only set it if we actually have a sleep impl.
        if let Some(sleep_impl) = sleep_impl.clone() {
            builder.set_sleep_impl(Some(sleep_impl));
        }
        let client = std::sync::Arc::new(builder.build());
        let mut clients = self.clients.write().await;
        clients.insert(connector_key, client.clone());
        Ok(client)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn smithy_client_pool_conf() -> SmithyClientPoolConf {
        SmithyClientPoolConf {
            sleep_impl: None,
            http_connector: None,
            retry_config: None,
            timeout_config: None,
        }
    }

    #[tokio::test]
    async fn test_client_pool_creation() {
        let client_pool = SmithyClientPool::new(smithy_client_pool_conf());

        let client = client_pool.get_or_create_client().await;
    }
}
