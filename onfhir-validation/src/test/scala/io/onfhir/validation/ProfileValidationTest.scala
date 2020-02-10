package io.onfhir.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirLiteralReference, FhirReference}
import io.onfhir.api.util.IOUtil
import io.onfhir.api.validation.IReferenceResolver
import io.onfhir.config.FhirConfigurationManager
import io.onfhir.r4.config.R4Configurator
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.io.Source

@RunWith(classOf[JUnitRunner])
class ProfileValidationTest extends Specification {
  //Initialize the environment
  val resourceProfiles = IOUtil.readStandardBundleFile("profiles-resources.json", Set("StructureDefinition")).flatMap(StructureDefinitionParser.parseProfile)
  val dataTypeProfiles = IOUtil.readStandardBundleFile("profiles-types.json", Set("StructureDefinition")).flatMap(StructureDefinitionParser.parseProfile)
  val otherProfiles = IOUtil.readStandardBundleFile("profiles-others.json", Set("StructureDefinition")).flatMap(StructureDefinitionParser.parseProfile)
  val extensions = IOUtil.readStandardBundleFile("extension-definitions.json", Set("StructureDefinition")).flatMap(StructureDefinitionParser.parseProfile)

  val valueSetsOrCodeSystems =
    IOUtil.readStandardBundleFile("valuesets.json", Set("ValueSet", "CodeSystem")) ++
      IOUtil.readStandardBundleFile("v3-codesystems.json", Set("ValueSet", "CodeSystem"))

  val extraProfiles = Seq(
    IOUtil.readInnerResource("/fhir/r4/profiles/MyObservation.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MySampledData.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MyMyObservation.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MyList.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MyList2.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MyExtension.StructureDefinition.json"),
    IOUtil.readInnerResource("/fhir/r4/profiles/MyExtension2.StructureDefinition.json")
  ).flatMap(StructureDefinitionParser.parseProfile)



  FhirConfigurationManager.initialize(new R4Configurator)
  FhirConfigurationManager.fhirConfig.profileRestrictions = (dataTypeProfiles ++ resourceProfiles ++ otherProfiles ++ extensions  ++ extraProfiles).map(p => p.url -> p).toMap
  FhirConfigurationManager.fhirConfig.valueSetRestrictions = TerminologyParser.parseValueSetBundle(valueSetsOrCodeSystems)

  //Reference resolver for tests
  var referenceResolverForLipidProfileSample = new IReferenceResolver {
    override def resolveReference(reference: FhirReference, currentResource: Resource): Option[Resource] = {
      reference match {
        case FhirLiteralReference(_, "Observation", rid, _) =>
          rid match {
            case "cholesterol" =>
              Some(IOUtil.readInnerResource("/fhir/r4/dreport/cholesterol.json"))
            case "triglyceride" =>
              Some(IOUtil.readInnerResource("/fhir/r4/dreport/tryglyceride.json"))
            case "hdlcholesterol" =>
              Some(IOUtil.readInnerResource("/fhir/r4/dreport/hdlcholesterol.json"))
            case "ldlcholesterol" =>
              Some(IOUtil.readInnerResource("/fhir/r4/dreport/ldlcholesterol.json"))
            case "extra" =>
              Some(IOUtil.readInnerResource("/fhir/r4/valid/observation-bp.json"))
            case _ => None
          }
        case _ => None
      }
    }

    /**
     * Check if a referenced resource exist
     *
     * @param reference FHIR  reference
     * @param profiles  Profiles that resource is expected to conform
     */
    override def isReferencedResourceExist(reference: FhirReference, profiles: Set[String]): Boolean = {
      reference match {
        case FhirLiteralReference(_, "Observation", rid, _) =>
          rid match {
            case "cholesterol" => profiles.isEmpty || profiles.contains("http://hl7.org/fhir/StructureDefinition/cholesterol")
            case "triglyceride" => profiles.isEmpty || profiles.contains("http://hl7.org/fhir/StructureDefinition/triglyceride")
            case "hdlcholesterol" => profiles.isEmpty || profiles.contains("http://hl7.org/fhir/StructureDefinition/hdlcholesterol")
            case "ldlcholesterol" => profiles.isEmpty || profiles.contains("http://hl7.org/fhir/StructureDefinition/ldlcholesterol")
            case "extra" =>  profiles.isEmpty
            case _ => false
          }
        case FhirLiteralReference(_, "Patient", "pat2", _) => true
        case FhirLiteralReference(_, "Organization", "1832473e-2fe0-452d-abe9-3cdb9879522f", _) => true
        case _ => false
      }
    }
  }



  sequential
  "ProfileValidation" should {
   "validate a valid FHIR resource against base definitions" in {
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://hl7.org/fhir/StructureDefinition/Observation")
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-glucose.json")).mkString).asInstanceOf[JObject]
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-erythrocyte.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-bp.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-group.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-reject.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)
    }

    "not validate an invalid FHIR resource against base definitions" in {
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://hl7.org/fhir/StructureDefinition/Observation")
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/invalid/observation-invalid-cardinality.json")).mkString).asInstanceOf[JObject]
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(false)
      issues.exists(i => i.location.head == "identifier") mustEqual(true)  // Should be an array but given as object
      issues.exists(_.location.head == "code.coding") mustEqual(true) //Should an array but given as object
      issues.exists(_.location.head == "subject") mustEqual(true) //Should an array but given as object
      issues.exists(i => i.location.head == "status") mustEqual(true) //Is required but not given

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/invalid/observation-invalid-basics.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(false)
      issues.exists(i => i.location.head == "status") mustEqual(true) //Invalid primitive empty code
      issues.exists(i => i.location.head == "extraElement") mustEqual(true) //Invalid extra element
      issues.exists(i => i.location.head == "category[0].coding[0].display") mustEqual(true) //Invalid primitive empty string
      issues.exists(i => i.location.head == "component[0].valueQuantity.value") mustEqual(true) //Invalid decimal (decimal as string)
      issues.exists(i => i.location.head == "component[0].interpretation[0].coding[0].extraElement") mustEqual(true) //Invalid extra element in inner parts
      issues.exists(i => i.location.head == "extension[0].url") mustEqual(true) //Required but not exist
      issues.exists(i => i.location.head == "extension[0]")  mustEqual(true) //Constraint failure both extension has value and extension

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/invalid/observation-invalid-complex.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(false)
      issues.exists(i => i.location.head == "status" && i.severity == "error") mustEqual true
      issues.exists(i => i.location.head == "category[0]" && i.severity == "warning") mustEqual true
      issues.exists(i => i.location.head == "interpretation[0]" && i.severity == "warning") mustEqual true
      issues.exists(i => i.location.head == "component[0].interpretation[0]" && i.severity == "warning") mustEqual true
    }

    "validate a valid FHIR resource against profile" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-my.json")).mkString).asInstanceOf[JObject]
      var fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyObservation")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)

      //Genetics example for fhir-observation-genetics profiles
      fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://hl7.org/fhir/StructureDefinition/observation-genetics")

      observation = JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/genetics/observation-example-diplotype1.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)

      observation = JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/genetics/observation-example-haplotype1.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)

      observation = JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/genetics/observation-example-TPMT-haplotype-one.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)

      observation = JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/genetics/observation-example-genetics-brcapat.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)
    }

    "not validate an invalid FHIR resource against profile" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/invalid/observation-my-invalid.json")).mkString).asInstanceOf[JObject]
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyObservation")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(false)
      issues.exists(i => i.location.head == "identifier[0].system" && i.severity == "error") mustEqual true //Slice indicates system as required
      issues.exists(i => i.location.head == "status" && i.severity == "error") mustEqual true //Fixed value error
      issues.exists(i => i.location.head == "category" && i.severity == "error") mustEqual true //Not array error
      issues.exists(i => i.location.head == "code" && i.severity == "error") mustEqual true //Shpuld not be array error
      issues.exists(i => i.location.head == "subject" && i.severity == "error" && i.diagnostics.exists(d => d.contains("expected target types"))) mustEqual true //Reference type does not match
      issues.exists(i => i.location.head == "subject" && i.severity == "error" && i.diagnostics.exists(d => d.contains("version independent"))) mustEqual true // Reference has version although it is stated as independent
      issues.exists(i => i.location.head == "encounter" && i.severity == "error" && i.diagnostics.exists(d => d.contains("version specific"))) //Reference should be version specific
      issues.exists(i => i.location.head == "encounter" && i.severity == "error" && i.diagnostics.exists(d => d.contains("expected target types"))) //Reference should be version specific
      issues.exists(i => i.location.head == "effectiveDateTime" && i.severity == "error") mustEqual true //Invalid date time format
      issues.exists(i => i.location.head == "issued" && i.severity == "error") mustEqual true //Invalid instant format
      issues.exists(i => i.location.head == "note[0].authorString" && i.severity == "error") mustEqual true //Exceed max length
      issues.exists(i => i.location.head == "note[0].text" && i.severity == "error") //Required field is missing
      issues.exists(i => i.location.head == "component[0].valueSampledData.factor" && i.severity == "error") mustEqual true  //Fixed value error
      issues.exists(i => i.location.head == "component[0].valueSampledData.upperLimit" && i.severity == "error") mustEqual true //Invalid decimal (given as string)
      issues.exists(i => i.location.head == "component[0].valueSampledData.lowerLimit" && i.severity == "error") mustEqual true //DataType profile, Missing element
      issues.exists(i => i.location.head == "component[0].valueSampledData.origin" && i.severity == "error") mustEqual true //DataType profile, missing element
      issues.exists(i => i.location.head == "component[6].valueString" && i.severity == "error") mustEqual true  //Slice, wrong data type
      issues.exists(i => i.location.head == "component[7].valueInteger" && i.severity == "error") mustEqual true  //Slice, wrong data type
      issues.exists(i => i.location.head == "component" && i.severity == "error") mustEqual true  //Slice cardinality
      issues.exists(i => i.location.head == "component[1].interpretation" && i.severity == "error") mustEqual true //Slicing match, required field is missing
      issues.exists(i => i.location.head == "component[1].referenceRange" && i.severity == "error") mustEqual true //Slicing match, required field is missing
      issues.exists(i => i.location.head == "basedOn" && i.severity == "error") mustEqual true//Missing element
    }

    "validate a valid FHIR resource against derived profile" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/valid/observation-mymy.json")).mkString).asInstanceOf[JObject]
      var fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyMyObservation")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(true)
    }


    "not validate an invalid FHIR resource against derived profile" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/invalid/observation-mymy-invalid.json")).mkString).asInstanceOf[JObject]
      var fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyMyObservation")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.isEmpty mustEqual(false)

      issues.exists(i => i.location.head == "method" && i.severity == "error") mustEqual true //method should not be used
      issues.exists(i => i.location.head == "component[4].valueInteger" && i.severity == "error") mustEqual true //Error according to the base profile
      issues.exists(i => i.location.head == "interpretation" && i.severity == "error") mustEqual true //required element according to this new derived profile
      issues.exists(i => i.location.head == "component" && i.severity == "error") mustEqual true
    }

    "validate a valid resource with slicing that has resolve() expression in discriminator" in {
      //Make reference validation policy as enforced for DiagnosticReport
      val obsConf = FhirConfigurationManager.fhirConfig.profileConfigurations("DiagnosticReport")
      FhirConfigurationManager.fhirConfig.profileConfigurations = FhirConfigurationManager.fhirConfig.profileConfigurations ++ Map("DiagnosticReport" -> obsConf.copy(referencePolicies = Set("enforced")))

      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids.json")).mkString).asInstanceOf[JObject]
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://hl7.org/fhir/StructureDefinition/lipidprofile", referenceResolverForLipidProfileSample)
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids2.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)
    }

    "not validate an invalid FHIR resource with slicing that has resolve() expression in discriminator" in {
      //Make reference validation policy as enforced for DiagnosticReport
      val obsConf = FhirConfigurationManager.fhirConfig.profileConfigurations("DiagnosticReport")
      FhirConfigurationManager.fhirConfig.profileConfigurations = FhirConfigurationManager.fhirConfig.profileConfigurations ++ Map("DiagnosticReport" -> obsConf.copy(referencePolicies = Set("enforced")))

      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://hl7.org/fhir/StructureDefinition/lipidprofile", referenceResolverForLipidProfileSample)

      //Missing reference to a cholesterol profile
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-missing-cholesterol.json")).mkString).asInstanceOf[JObject]
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "Cholesterol"))) mustEqual true

      //Missing reference to a tryglyceride profile
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-missing-tryglyceride.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "Triglyceride"))) mustEqual true

      //Extra reference to a tryglyceride profile (max cardinality 1, cardinality 2)
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-extra-tryglyceride.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "Triglyceride"))) mustEqual true

      //Extra reference used in a closed slicing
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-extra-reference-for-closed-slicing.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "closed"))) mustEqual true

      //Unordered references in a ordered slicing
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-unordered.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "Triglyceride") && d.contains("index 2 should be in index 1"))) mustEqual true
      issues.exists(i => i.location.head == "result" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "HDLCholesterol") && d.contains("index 1 should be in index 3"))) mustEqual true

      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/dreport/diagnosticreport-example-lipids-invalid-missing-reference.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.severity == "error" && i.diagnostics.exists(d => d.contains( "Referenced resource Patient/pat3 does not exist"))) mustEqual true
    }

    "validate a valid resource with slicing with discriminator type: 'type' and 'exists'" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-valid.json")).mkString).asInstanceOf[JObject]
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyList")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)
    }

    "not validate a invalid resource with slicing with discriminator type: 'type' and 'exists'" in {
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyList")

      //Missing element for withAuthor slice although the min cardinality is one
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-invalid-missing-withAuthor.json")).mkString).asInstanceOf[JObject]
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "note" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "WithAuthor"))) mustEqual true

      //Invalid slice matching (invalid target Reference type and missing time element)
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-invalid-withAuthor-uncompliant-slice.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "note[0].authorReference" && i.severity == "error") mustEqual true
      issues.exists(i => i.location.head == "note[0].time" && i.severity == "error") mustEqual true

      //Invalid slice matching (extra time element)
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-invalid-withoutAuthor-extra-uncompliant-slice.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "note[2].time" && i.severity == "error") mustEqual true

      //Missing element for Patients slice although the min cardinality is one
      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-invalid-missing-Patients-slice.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "entry" && i.severity == "error" && i.diagnostics.exists(d => d.contains( "Patients"))) mustEqual true


      observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist-invalid-Patients-uncompliant-slice.json")).mkString).asInstanceOf[JObject]
      issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "entry[1].date" && i.severity == "error") mustEqual true
    }


    "validate a valid resource with slicing with extension in discriminator path" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist2-valid.json")).mkString).asInstanceOf[JObject]
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyList2")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(false)
    }

    "not validate a invalid resource with slicing with extension in discriminator path" in {
      var observation =  JsonMethods.parse(Source.fromInputStream(getClass.getResourceAsStream("/fhir/r4/list/mylist2-invalid.json")).mkString).asInstanceOf[JObject]
      val fhirContentValidator = FhirContentValidator(FhirConfigurationManager.fhirConfig, "http://example.org/fhir/StructureDefinition/MyList2")
      var issues = fhirContentValidator.validateComplexContent(observation)
      issues.exists(_.severity == "error") mustEqual(true)
      issues.exists(i => i.location.head == "entry[1].item" && i.severity == "error") mustEqual true //Referenced type does not match
      issues.exists(i => i.location.head == "entry[2].item.extension[1].valueString" && i.severity == "error") mustEqual true //legnth of string exceeds max length
      issues.exists(i => i.location.head == "entry" && i.severity == "error" && i.diagnostics.exists(d => d.contains("e2"))) mustEqual true //slice e2 has minimum cardinality 1
      issues.exists(i => i.location.head == "entry" && i.severity == "error" && i.diagnostics.exists(d => d.contains("openAtEnd"))) mustEqual true //extra entry is given in beginning
    }

  }
}