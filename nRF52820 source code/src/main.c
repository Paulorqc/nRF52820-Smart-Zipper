#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/gatt.h>
#include <hal/nrf_gpio.h>
#include <hal/nrf_gpiote.h>
#include <hal/nrf_power.h>

#define LED_P6 6
#define LED_P7 7
#define HALL_SENSOR_PIN 20

static char hall_str[2] = "0";
static char last_state = '0';
static struct bt_conn *current_conn;

K_SEM_DEFINE(hall_sem, 0, 1);

static struct bt_uuid_16 svc_uuid = BT_UUID_INIT_16(0xFF03);
static struct bt_uuid_16 chr_uuid = BT_UUID_INIT_16(0xFF04);

static ssize_t read_hall_str(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                             void *buf, uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset, hall_str, 1);
}

BT_GATT_SERVICE_DEFINE(hall_svc,
    BT_GATT_PRIMARY_SERVICE(&svc_uuid),
    BT_GATT_CHARACTERISTIC(&chr_uuid.uuid,
           BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
           BT_GATT_PERM_READ,
           read_hall_str, NULL, hall_str),
    BT_GATT_CCC(NULL, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

static const struct bt_data ad[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
    BT_DATA(BT_DATA_NAME_COMPLETE, "SmartZipper", 11),
};

static void connected(struct bt_conn *conn, uint8_t err)
{
    if (!err) {
        current_conn = bt_conn_ref(conn);
        k_sem_give(&hall_sem);
    }
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    if (current_conn) {
        bt_conn_unref(current_conn);
        current_conn = NULL;
    }
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

ISR_DIRECT_DECLARE(gpiote_isr)
{
    if (nrf_gpiote_event_check(NRF_GPIOTE, NRF_GPIOTE_EVENT_IN_0)) {
        nrf_gpiote_event_clear(NRF_GPIOTE, NRF_GPIOTE_EVENT_IN_0);
        k_sem_give(&hall_sem);
    }
    ISR_DIRECT_PM();
    return 1;
}

static void setup_hall_interrupt(void)
{
    nrf_gpio_cfg_input(HALL_SENSOR_PIN, NRF_GPIO_PIN_PULLUP);
    nrf_gpiote_event_configure(NRF_GPIOTE, 0, HALL_SENSOR_PIN, NRF_GPIOTE_POLARITY_TOGGLE);
    nrf_gpiote_event_enable(NRF_GPIOTE, 0);
    nrf_gpiote_int_enable(NRF_GPIOTE, NRF_GPIOTE_INT_IN0_MASK);
    IRQ_DIRECT_CONNECT(GPIOTE_IRQn, 1, gpiote_isr, 0);
    irq_enable(GPIOTE_IRQn);
}

static void bt_ready_cb(int err)
{
    if (err) {
        return;
    }

    struct bt_le_adv_param adv = {
        .id = BT_ID_DEFAULT,
        .options = BT_LE_ADV_OPT_CONN,
        .interval_min = 0x0640,
        .interval_max = 0x0C80,
    };

    bt_le_adv_start(&adv, ad, ARRAY_SIZE(ad), NULL, 0);
}

int main(void)
{
    NRF_POWER->DCDCEN = 0;
    
    nrf_gpio_cfg_output(LED_P6);
    nrf_gpio_cfg_output(LED_P7);
    nrf_gpio_pin_write(LED_P6, 0);
    nrf_gpio_pin_write(LED_P7, 0);

    setup_hall_interrupt();
    
    uint32_t pin = nrf_gpio_pin_read(HALL_SENSOR_PIN);
    hall_str[0] = (pin == 0) ? '1' : '0';
    last_state = hall_str[0];

    bt_enable(bt_ready_cb);

    while (1) {
        k_sem_take(&hall_sem, K_FOREVER);
        k_msleep(10);
        
        pin = nrf_gpio_pin_read(HALL_SENSOR_PIN);
        hall_str[0] = (pin == 0) ? '1' : '0';
        
        if (hall_str[0] != last_state) {
            last_state = hall_str[0];
            
            if (current_conn) {
                bt_gatt_notify(current_conn, &hall_svc.attrs[2], hall_str, 1);
            }
        }
    }
    return 0;
}