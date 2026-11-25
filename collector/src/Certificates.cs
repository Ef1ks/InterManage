using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using Org.BouncyCastle.Asn1;
using Org.BouncyCastle.Asn1.X509;
using Org.BouncyCastle.X509;

namespace Intermanage.Collector
{
    /// <summary>
    /// Sprawdzenie certyfikatu serwera: Windows CE 6.0 nie zna dzisiejszych urzędów, więc program ma własną listę
    /// (korzenie.pem, w zasobach). Łańcuch musi dojść do któregoś z nich podpis po podpisie, a certyfikat serwera musi
    /// być wystawiony na nazwę, z którą się łączymy, i być ważny.
    /// </summary>
    internal static class Certificates
    {
        private const int MaxDepth = 6;
        private const string ServerAuth = "1.3.6.1.5.5.7.3.1";
        private const int KeyCertSign = 5;
        private static List<X509Certificate> roots;

        /// <summary>Pusty tekst, gdy certyfikat jest dobry; inaczej powód odrzucenia.</summary>
        public static string Check(IList chain, string host)
        {
            if (chain.Count == 0)
            {
                return "serwer nie przedstawił certyfikatu";
            }
            // Po zimnym restarcie zegar kolektora stoi w przeszłości: wtedy liczy się dzień zbudowania programu.
            DateTime now = DateTime.UtcNow;
            bool clockSet = now > BuildInfo.BuiltAt;
            if (!clockSet)
            {
                now = BuildInfo.BuiltAt;
            }

            X509Certificate current = (X509Certificate)chain[0];
            if (!MatchesHost(current, host))
            {
                return "certyfikat nie jest wystawiony na " + host;
            }
            IList usage = current.GetExtendedKeyUsage();
            if (usage != null && !usage.Contains(ServerAuth))
            {
                return "certyfikat nie jest certyfikatem serwera";
            }
            for (int depth = 0; depth < MaxDepth; depth++)
            {
                if (now > current.NotAfter || (clockSet && now < current.NotBefore))
                {
                    return "certyfikat " + current.SubjectDN + " jest nieważny (data kolektora: "
                        + DateTime.Now.ToString("yyyy-MM-dd") + ")";
                }
                foreach (X509Certificate root in Roots)
                {
                    if (root.SubjectDN.Equivalent(current.IssuerDN) && Signed(current, root))
                    {
                        return now > root.NotAfter ? "urząd certyfikatu wygasł, potrzebna nowa wersja programu" : "";
                    }
                }
                X509Certificate issuer = null;
                foreach (X509Certificate candidate in chain)
                {
                    if (candidate.SubjectDN.Equivalent(current.IssuerDN) && !candidate.Equals(current)
                        && IsAuthority(candidate) && Signed(current, candidate))
                    {
                        issuer = candidate;
                        break;
                    }
                }
                if (issuer == null)
                {
                    return "nieznany urząd certyfikatu: " + current.IssuerDN + " (potrzebna nowa wersja programu)";
                }
                current = issuer;
            }
            return "za długi łańcuch certyfikatów";
        }

        private static bool IsAuthority(X509Certificate certificate)
        {
            bool[] usage = certificate.GetKeyUsage();
            return certificate.GetBasicConstraints() >= 0
                && (usage == null || (usage.Length > KeyCertSign && usage[KeyCertSign]));
        }

        private static bool Signed(X509Certificate certificate, X509Certificate issuer)
        {
            try
            {
                certificate.Verify(issuer.GetPublicKey());
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        private static bool MatchesHost(X509Certificate certificate, string host)
        {
            Asn1OctetString extension = certificate.GetExtensionValue(X509Extensions.SubjectAlternativeName);
            if (extension == null)
            {
                return false;
            }
            GeneralNames names = GeneralNames.GetInstance(Asn1Object.FromByteArray(extension.GetOctets()));
            foreach (GeneralName name in names.GetNames())
            {
                if (name.TagNo == GeneralName.DnsName && HostMatches(name.Name.ToString(), host))
                {
                    return true;
                }
            }
            return false;
        }

        private static bool HostMatches(string pattern, string host)
        {
            pattern = pattern.ToLower();
            host = host.ToLower();
            if (!pattern.StartsWith("*."))
            {
                return pattern == host;
            }
            int dot = host.IndexOf('.');
            return dot > 0 && host.Substring(dot) == pattern.Substring(1);
        }

        private static List<X509Certificate> Roots
        {
            get
            {
                if (roots == null)
                {
                    List<X509Certificate> loaded = new List<X509Certificate>();
                    X509CertificateParser parser = new X509CertificateParser();
                    using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("korzenie.pem"))
                    {
                        foreach (X509Certificate root in parser.ReadCertificates(stream))
                        {
                            loaded.Add(root);
                        }
                    }
                    roots = loaded;
                }
                return roots;
            }
        }
    }
}
