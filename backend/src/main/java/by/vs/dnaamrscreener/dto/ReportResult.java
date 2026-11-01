package by.vs.dnaamrscreener.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReportResult {
    private String geneId;           // идентификатор маркера устойчивости
    private String antibiotic;       // пораженный класс антимикробных препаратов
    private double matchPercentage;  // процент совпадения после выравнивания
    private String status;           // медицинский статус с расчетом покрытия
}
