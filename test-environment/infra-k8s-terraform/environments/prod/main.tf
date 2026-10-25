resource "kubernetes_config_map" "order_service" {
  metadata {
    name      = "order-service-config"
    namespace = "prod"
  }
  data = {
    PAYMENT_SERVICE_URL  = "http://payment-service.prod.svc.cluster.local"
    SHIPPING_SERVICE_URL = "http://shipping-service.prod.svc.cluster.local"
  }
}
