package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.basedelivery.component.BaseDeliveryParser;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryRow;
import com.intermanage.backend.basedelivery.service.BaseDeliveryPortalService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Dokument BL z portalu Grupy Muszkieterów (kształt z /api/documentbl/document/ z 14.11.2026) przez parser bonu. */
class BaseDeliveryPortalTests {

    private static final String DOCUMENT = """
            {"document":"09516052152","shopID":"10023","date_import":"2026-11-14","products":{
            "201373693":{"Sklep":"10023","Data_Bl":"2026-11-14","Nr_Bl":"09516052152","NrNosnika":"42902163",
              "ITM8":"00081992","Nazwa_Produktu":"ZUPA BLYSK\\/KURCZAK CURRY 70G","Marka":"VIFON","PAK":"24",
              "Kaucja":"NIE","Data_DLV":"2027-10-31","Dni_dla_Sklepu":"67","EAN":"5901882110069",
              "PKWiU":"10.73.11.0","Spolka":"538","Kod_MEA":"","Nr_Zam_ITM":"10023317","Coli_Do_Przyg":"2",
              "Coli_Przyg":"2","Ciezar_REEL":"0.000","C_Cesja":"1.85","C_Publi":"2.09","VAT":"5",
              "Kwota_VAT":"4.44","Kwota_HT":"88.80","Kwota_TTC":"93.24","Kod_Sub":"","Rejon":"01","Rodz":"15",
              "Pod_Rodz":"04","Typ_Sprz":"0.07","Jed_Sprz":"K","CN":"19023010","GTU":"-----","SP":"N","CNUF":"00538"},
            "201373695":{"Sklep":"10023","Data_Bl":"2026-11-14","Nr_Bl":"09516052152","NrNosnika":"42902870",
              "ITM8":"00184995","Nazwa_Produktu":"CZEK.MILKA -HAZELNUT 90G","Marka":"MILKA","PAK":"24",
              "Kaucja":"PLA","Data_DLV":"2027-07-06","Dni_dla_Sklepu":"110","EAN":"7622202262449",
              "PKWiU":"----------","Spolka":"538","Kod_MEA":"81534","Nr_Zam_ITM":"10023317","Coli_Do_Przyg":"1",
              "Coli_Przyg":"1","Ciezar_REEL":null,"C_Cesja":"4.69","C_Publi":"6.99","VAT":"23",
              "Kwota_VAT":"25.89","Kwota_HT":"112.56","Kwota_TTC":"138.45","Kod_Sub":"","Rejon":"01","Rodz":"06",
              "Pod_Rodz":"05","Typ_Sprz":"0.09","Jed_Sprz":"K","CN":"18063210","GTU":"","SP":"N","CNUF":"00538"}},
            "sum":{"SUMA_Netto":201.36},"info":"ok"}
            """;

    @Test
    void portalDocumentReadsLikeBaseDeliveryFile() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree(DOCUMENT);

        BaseDeliveryParser.Parsed parsed = new BaseDeliveryParser()
                .parse(BaseDeliveryPortalService.sheet(body.path("products")));

        assertThat(parsed.storeNumber()).isEqualTo("10023");
        assertThat(parsed.number()).isEqualTo("09516052152");
        assertThat(parsed.deliveryDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(parsed.rows()).hasSize(2);
        BaseDeliveryRow first = parsed.rows().getFirst();
        assertThat(first.itm8()).isEqualTo("00081992");
        assertThat(first.name()).isEqualTo("ZUPA BLYSK/KURCZAK CURRY 70G");
        assertThat(first.purchasePrice()).isEqualByComparingTo("1.85");
        assertThat(first.coliPrepared()).isEqualByComparingTo("2");
        assertThat(first.weight()).isNull();
        assertThat(first.supplierCode()).isEqualTo("538");
        BaseDeliveryRow second = parsed.rows().get(1);
        assertThat(second.deposit()).isEqualTo("PLA");
        assertThat(second.meaCode()).isEqualTo("81534");
        assertThat(second.vatRate()).isEqualByComparingTo(BigDecimal.valueOf(23));
    }
}
